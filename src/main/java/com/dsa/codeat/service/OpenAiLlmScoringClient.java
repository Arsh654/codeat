package com.dsa.codeat.service;

import com.dsa.codeat.config.LlmProperties;
import com.dsa.codeat.model.AnalyzeRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class OpenAiLlmScoringClient implements LlmScoringClient {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmScoringClient.class);
    private static final int MIN_STRONG_EVIDENCE_TESTS = 20;

    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private boolean usedFallback = false;
    private long lastRequestTime = 0;

    public OpenAiLlmScoringClient(LlmProperties llmProperties) {
        this.llmProperties = llmProperties;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    @Override
    public LlmScoreResult score(AnalyzeRequest request) {
        usedFallback = false;
        String prompt = buildPrompt(request);
        log.info("LLM scoring started. provider={}, model={}", llmProperties.getProvider(), llmProperties.getModel());

        try {
            JsonNode result = requestCompletionAsJson(systemPrompt(), prompt);

            String verdict = normalizeVerdict(text(result, "leetcodeLikelyVerdict"));
            String matchedProblemId = text(result, "matchedProblemId");
            String matchedProblemTitle = text(result, "matchedProblemTitle");
            double accuracy = clampPercent(result.path("accuracyPercentage").asDouble(0.0));
            double confidence = clampPercent(result.path("confidencePercentage").asDouble(0.0));
            int estimatedPassed = clampCount(result.path("estimatedPassedTestCases").asInt(0));
            int estimatedTotal = clampCount(result.path("estimatedTotalTestCases").asInt(0));
            boolean compileLikelyValid = result.path("compileLikelyValid").asBoolean(true);
            String feedback = text(result, "feedback");
            List<String> strengths = toStringList(result.path("strengths"));
            List<String> improvements = toStringList(result.path("improvements"));
            List<FailingScenarioResult> failingScenarios = sanitizeFailingScenarios(toFailingScenarioList(result.path("failingScenarios")));
            String reviewSummary = "";
            Double styleScorePercentage = null;
            List<String> styleFindings = List.of();
            List<String> reviewSuggestions = List.of();
            failingScenarios = applyProblemSpecificScenarioFilters(matchedProblemId, matchedProblemTitle, failingScenarios);

            if (shouldBackfillScenarios(verdict, accuracy, estimatedPassed, estimatedTotal, compileLikelyValid, failingScenarios)) {
                List<FailingScenarioResult> backfilled = requestFailingScenariosOnly(request, verdict, accuracy);
                if (!backfilled.isEmpty()) {
                    failingScenarios = sanitizeFailingScenarios(backfilled);
                }
            }

            if ("PASS".equals(verdict)) {
                PassChallengeResult challenge = requestPassChallenge(request, accuracy, feedback);
                if (challenge.hasLikelyFailingCase()) {
                    List<FailingScenarioResult> challengeScenarios = sanitizeFailingScenarios(challenge.failingScenarios());
                    challengeScenarios = applyProblemSpecificScenarioFilters(matchedProblemId, matchedProblemTitle, challengeScenarios);
                    verdict = "FAIL";
                    if (!challengeScenarios.isEmpty()) {
                        failingScenarios = challengeScenarios;
                    }
                    feedback = feedback + " Counterexample risk detected by adversarial check.";
                }
            }

            accuracy = reconcileAccuracy(accuracy, estimatedPassed, estimatedTotal);
            verdict = enforceVerdictConsistency(verdict, accuracy, estimatedPassed, estimatedTotal, confidence, compileLikelyValid, failingScenarios);
            if (isStrongPassEvidence(accuracy, confidence, estimatedPassed, estimatedTotal, compileLikelyValid)) {
                verdict = "PASS";
            }
            if (shouldPromoteToPass(
                    matchedProblemId,
                    matchedProblemTitle,
                    accuracy,
                    confidence,
                    estimatedPassed,
                    estimatedTotal,
                    compileLikelyValid,
                    failingScenarios
            )) {
                verdict = "PASS";
            }
            if ("PASS".equals(verdict)) {
                accuracy = 100.0;
                confidence = Math.max(confidence, 95.0);
                failingScenarios = List.of();
                feedback = feedback.replace(" Counterexample risk detected by adversarial check.", "").trim();
            }
            if ("FAIL".equals(verdict) || "MAY_PASS".equals(verdict)) {
                ScorePair normalized = normalizeNonPassScores(
                        verdict,
                        accuracy,
                        confidence,
                        estimatedPassed,
                        estimatedTotal,
                        compileLikelyValid,
                        failingScenarios.size()
                );
                accuracy = normalized.accuracy();
                confidence = normalized.confidence();
            }

            VerdictScore strictScore = applyStrictEvidenceCalibration(
                    verdict,
                    accuracy,
                    confidence,
                    estimatedPassed,
                    estimatedTotal,
                    compileLikelyValid,
                    failingScenarios.size()
            );
            verdict = strictScore.verdict();
            accuracy = strictScore.accuracy();
            confidence = strictScore.confidence();

            if ("PASS".equals(verdict) && accuracy >= 100.0 && compileLikelyValid) {
                try {
                    CodeReviewResult codeReviewResult = requestCodeReviewForPassingSolution(request, feedback);
                    reviewSummary = codeReviewResult.reviewSummary();
                    styleScorePercentage = codeReviewResult.styleScorePercentage();
                    styleFindings = codeReviewResult.styleFindings();
                    reviewSuggestions = codeReviewResult.reviewSuggestions();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Code review generation interrupted for PASS solution");
                } catch (IOException | IllegalStateException e) {
                    log.warn("Could not generate PASS code review: {}", e.getMessage());
                }
            }

            String actualModelUsed = usedFallback
                ? llmProperties.getFallback().getModel()
                : llmProperties.getModel();

            LlmScoreResult llmScoreResult = new LlmScoreResult(
                    matchedProblemId,
                    matchedProblemTitle,
                    accuracy,
                    confidence,
                    verdict,
                    estimatedPassed,
                    estimatedTotal,
                    compileLikelyValid,
                    feedback,
                    strengths,
                    improvements,
                    failingScenarios,
                    reviewSummary,
                    styleScorePercentage,
                    styleFindings,
                    reviewSuggestions,
                    actualModelUsed
            );
            String providerUsed = usedFallback
                ? llmProperties.getFallback().getProvider() + " (fallback)"
                : llmProperties.getProvider();
            log.info("LLM scoring completed. provider={}, model={}, matchedProblemId={}, verdict={}, accuracy={}, confidence={}",
                    providerUsed,
                    actualModelUsed,
                    llmScoreResult.matchedProblemId(),
                    llmScoreResult.leetcodeLikelyVerdict(),
                    llmScoreResult.accuracyPercentage(),
                    llmScoreResult.confidencePercentage());
            return llmScoreResult;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("LLM scoring interrupted");
            throw new IllegalStateException("Failed to call LLM scorer", e);
        } catch (IOException e) {
            log.error("LLM scoring I/O failure: {}", e.getMessage());
            throw new IllegalStateException("Failed to call LLM scorer", e);
        }
    }

    private JsonNode requestCompletionAsJson(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
        applyRateLimit();
        try {
            return callLlmApi(
                llmProperties.resolvedApiUrl(),
                llmProperties.getApiKey(),
                llmProperties.getModel(),
                systemPrompt,
                userPrompt,
                false
            );
        } catch (IllegalStateException e) {
            if (e.getMessage().contains("Rate limit exceeded") && llmProperties.getFallback().isEnabled()) {
                log.warn("Primary LLM rate limited, attempting fallback to {}", llmProperties.getFallback().getProvider());
                usedFallback = true;
                return callLlmApi(
                    llmProperties.resolvedFallbackApiUrl(),
                    llmProperties.getFallback().getApiKey(),
                    llmProperties.getFallback().getModel(),
                    systemPrompt,
                    userPrompt,
                    true
                );
            }
            throw e;
        }
    }

    private JsonNode callLlmApi(
            String apiUrl,
            String apiKey,
            String model,
            String systemPrompt,
            String userPrompt,
            boolean isFallback
    ) throws IOException, InterruptedException {
        String requestBody = objectMapper.writeValueAsString(
                Map.of(
                        "model", model,
                        "temperature", 0.1,
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt)
                        )
                )
        );

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        if (apiUrl.contains("openrouter.ai")) {
            requestBuilder.header("HTTP-Referer", "https://github.com/codeat-dsa-analyzer");
            requestBuilder.header("X-Title", "Codeat DSA Analyzer");
        }

        HttpRequest httpRequest = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 429) {
            String provider = isFallback ? "Fallback LLM" : "Primary LLM";
            throw new IllegalStateException("Rate limit exceeded on " + provider + ". Please wait before analyzing again.");
        }
        if (response.statusCode() == 401) {
            String provider = isFallback ? "fallback" : "primary";
            throw new IllegalStateException("Invalid API key for " + provider + " LLM. Please check configuration.");
        }
        if (response.statusCode() >= 400) {
            String errorDetail = response.body() != null && response.body().length() < 200
                ? " - " + response.body()
                : "";
            throw new IllegalStateException("LLM request failed with status " + response.statusCode() + errorDetail);
        }

        JsonNode root = objectMapper.readTree(response.body());
        String content = root.path("choices").path(0).path("message").path("content").asText();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("LLM returned empty content");
        }

        return objectMapper.readTree(stripMarkdownCodeFence(content));
    }

    private List<FailingScenarioResult> requestFailingScenariosOnly(AnalyzeRequest request, String verdict, double accuracy)
            throws IOException, InterruptedException {
        String system = "Return ONLY valid JSON array where each element has keys: inputExample, expectedBehavior, " +
                "predictedBehavior, reason. Provide 2 to 4 concrete likely failing scenarios for the submitted code.";

        String user = "Problem hint: " + safe(request.problemId()) + "\n" +
                "Statement hint: " + safe(request.problemStatement()) + "\n" +
                "Class name: " + safe(request.className()) + "\n" +
                "Current verdict: " + verdict + "\n" +
                "Current estimated accuracy: " + accuracy + "\n" +
                "Code:\n" + request.sourceCode();

        JsonNode node = requestCompletionAsJson(system, user);
        return toFailingScenarioList(node);
    }

    private PassChallengeResult requestPassChallenge(AnalyzeRequest request, double accuracy, String feedback)
            throws IOException, InterruptedException {
        String language = detectLanguage(request.sourceCode());
        String system = "You are a strict code judge. Return ONLY valid JSON with keys: " +
                "hasLikelyFailingCase (boolean), failingScenarios (array of objects with keys " +
                "inputExample, expectedBehavior, predictedBehavior, reason). " +
                "If any realistic hidden test can fail, set hasLikelyFailingCase=true and provide at least one scenario.";

        String user = "Review this " + language + " code aggressively for hidden-test failures.\\n" +
                "Current estimated accuracy: " + accuracy + "\\n" +
                "Current feedback: " + safe(feedback) + "\\n" +
                "Code:\\n" + request.sourceCode();

        JsonNode node = requestCompletionAsJson(system, user);
        boolean hasLikelyFailingCase = node.path("hasLikelyFailingCase").asBoolean(false);
        List<FailingScenarioResult> scenarios = toFailingScenarioList(node.path("failingScenarios"));
        return new PassChallengeResult(hasLikelyFailingCase || !scenarios.isEmpty(), scenarios);
    }

    private CodeReviewResult requestCodeReviewForPassingSolution(AnalyzeRequest request, String feedback)
            throws IOException, InterruptedException {
        String language = detectLanguage(request.sourceCode());
        String system = "You are a " + language + " code reviewer. Return ONLY valid JSON with keys: " +
                "reviewSummary (string), styleScorePercentage (number 0-100), styleFindings (array of strings), " +
                "reviewSuggestions (array of strings). Keep feedback concise and practical.";

        String user = "This code is predicted to pass with 100% confidence checks. " +
                "Provide maintainability and style review only (not correctness).\\n" +
                "Current analyzer feedback: " + safe(feedback) + "\\n" +
                "Code:\\n" + request.sourceCode();

        JsonNode node = requestCompletionAsJson(system, user);
        String summary = text(node, "reviewSummary");
        Double styleScore = nullablePercent(node.path("styleScorePercentage"));
        List<String> findings = toStringList(node.path("styleFindings")).stream().limit(4).toList();
        List<String> suggestions = toStringList(node.path("reviewSuggestions")).stream().limit(4).toList();
        return new CodeReviewResult(summary, styleScore, findings, suggestions);
    }

    private String systemPrompt() {
        return "You are an expert DSA evaluator. Return ONLY valid JSON with keys: " +
                "matchedProblemId, matchedProblemTitle, accuracyPercentage, confidencePercentage, " +
                "leetcodeLikelyVerdict, estimatedPassedTestCases, estimatedTotalTestCases, " +
                "compileLikelyValid, feedback, strengths, improvements, failingScenarios. " +
                "leetcodeLikelyVerdict must be one of PASS, MAY_PASS, FAIL, UNCERTAIN. " +
                "Detect the language from submitted code and only reference that language in feedback. " +
                "Do not call code Java unless it is clearly Java. " +
                "Keep feedback concise in 1 to 2 short sentences. " +
                "If verdict is FAIL OR accuracyPercentage < 100, failingScenarios must be a non-empty array of " +
                "objects with keys inputExample, expectedBehavior, predictedBehavior, reason.";
    }

    private String buildPrompt(AnalyzeRequest request) {
        String language = detectLanguage(request.sourceCode());
        StringBuilder prompt = new StringBuilder();
        if (request.problemId() != null && !request.problemId().isBlank()) {
            prompt.append("Problem ID hint: ").append(request.problemId().trim()).append("\\n");
        }
        if (request.problemStatement() != null && !request.problemStatement().isBlank()) {
            prompt.append("Problem statement hint: ").append(request.problemStatement().trim()).append("\\n");
        }
        if (request.className() != null && !request.className().isBlank()) {
            prompt.append("Class name hint: ").append(request.className().trim()).append("\\n");
        }
        prompt.append("Detected language: ").append(language).append("\\n")
                .append("Evaluate this ").append(language).append(" DSA solution and estimate correctness percentage.\\n")
                .append("Also predict if it will pass LeetCode style hidden tests.\\n")
                .append("If any risk exists, provide concrete failing scenarios.\\n")
                .append("Code:\\n")
                .append(request.sourceCode());
        return prompt.toString();
    }

    private boolean shouldBackfillScenarios(
            String verdict,
            double accuracy,
            int estimatedPassed,
            int estimatedTotal,
            boolean compileLikelyValid,
            List<FailingScenarioResult> failingScenarios
    ) {
        if (!failingScenarios.isEmpty()) {
            return false;
        }
        if (compileLikelyValid && estimatedTotal > 0 && estimatedPassed == estimatedTotal) {
            return false;
        }
        if ("FAIL".equals(verdict) || "UNCERTAIN".equals(verdict)) {
            return true;
        }
        return accuracy < 100.0;
    }

    private String enforceVerdictConsistency(
            String verdict,
            double accuracy,
            int estimatedPassed,
            int estimatedTotal,
            double confidence,
            boolean compileLikelyValid,
            List<FailingScenarioResult> failingScenarios
    ) {
        double passRate = estimatedTotal > 0 ? (estimatedPassed * 1.0) / estimatedTotal : -1.0;

        if (!compileLikelyValid) {
            return "FAIL";
        }
        if (estimatedTotal >= MIN_STRONG_EVIDENCE_TESTS
                && estimatedPassed == estimatedTotal
                && accuracy >= 99.0
                && confidence >= 93.0
                && failingScenarios.isEmpty()) {
            return "PASS";
        }
        if (estimatedTotal >= 10 && passRate >= 0.95 && accuracy >= 95.0 && confidence >= 88.0 && failingScenarios.isEmpty()) {
            return "MAY_PASS";
        }
        if (estimatedTotal > 0 && estimatedPassed < estimatedTotal) {
            return "FAIL";
        }
        if (!failingScenarios.isEmpty()) {
            return "FAIL";
        }
        if (accuracy < 100.0 && "PASS".equals(verdict)) {
            return "UNCERTAIN";
        }
        if ("FAIL".equals(verdict) && confidence < 85.0 && accuracy >= 95.0 && failingScenarios.isEmpty()) {
            return estimatedTotal > 0 && passRate >= 0.95 ? "MAY_PASS" : "UNCERTAIN";
        }
        return verdict;
    }

    private double reconcileAccuracy(double accuracy, int estimatedPassed, int estimatedTotal) {
        if (estimatedTotal <= 0) {
            return accuracy;
        }
        double derived = clampPercent((estimatedPassed * 100.0) / estimatedTotal);
        if (Math.abs(derived - accuracy) >= 5.0) {
            return derived;
        }
        return Math.max(accuracy, derived);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private List<String> toStringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .map(JsonNode::asText)
                .filter(s -> s != null && !s.isBlank())
                .toList();
    }

    private List<FailingScenarioResult> toFailingScenarioList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .map(item -> {
                    if (item.isObject()) {
                        return new FailingScenarioResult(
                                text(item, "inputExample"),
                                text(item, "expectedBehavior"),
                                text(item, "predictedBehavior"),
                                text(item, "reason")
                        );
                    }
                    String fallback = item.asText("");
                    if (fallback.isBlank()) {
                        return null;
                    }
                    return new FailingScenarioResult("", "", "", fallback);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private List<FailingScenarioResult> sanitizeFailingScenarios(List<FailingScenarioResult> scenarios) {
        return scenarios.stream()
                .filter(this::isCredibleScenario)
                .toList();
    }

    private boolean isCredibleScenario(FailingScenarioResult scenario) {
        if (scenario == null) {
            return false;
        }
        String input = safe(scenario.inputExample());
        String expected = safe(scenario.expectedBehavior());
        String predicted = safe(scenario.predictedBehavior());
        String reason = safe(scenario.reason());

        if ("N/A".equals(input) || "N/A".equals(reason)) {
            return false;
        }
        if (isLikelyPlaceholder(reason) || isLikelyPlaceholder(predicted)) {
            return false;
        }
        if (expected.equalsIgnoreCase(predicted)) {
            return false;
        }
        return true;
    }

    private boolean isLikelyPlaceholder(String value) {
        String normalized = value.toLowerCase();
        return normalized.contains("seems to work correctly")
                || normalized.contains("or incorrect result")
                || normalized.equals("n/a")
                || normalized.isBlank();
    }

    private List<FailingScenarioResult> applyProblemSpecificScenarioFilters(
            String matchedProblemId,
            String matchedProblemTitle,
            List<FailingScenarioResult> scenarios
    ) {
        if (!isLikelyTwoSum(matchedProblemId, matchedProblemTitle)) {
            return scenarios;
        }

        return scenarios.stream()
                .filter(s -> !isInvalidTwoSumScenario(s))
                .toList();
    }

    private boolean isInvalidTwoSumScenario(FailingScenarioResult scenario) {
        String input = scenario.inputExample() == null ? "" : scenario.inputExample().toLowerCase();
        String reason = scenario.reason() == null ? "" : scenario.reason().toLowerCase();
        String expected = scenario.expectedBehavior() == null ? "" : scenario.expectedBehavior().toLowerCase();
        String predicted = scenario.predictedBehavior() == null ? "" : scenario.predictedBehavior().toLowerCase();

        boolean emptyOrSingleArrayClaim = input.contains("[]")
                || input.contains("[ ]")
                || (input.contains("[") && input.contains("]") && !input.contains(","));
        if (emptyOrSingleArrayClaim) {
            return true;
        }

        boolean duplicateClaimAgainstKnownValidPattern = input.contains("[3, 3]")
                && reason.contains("duplicate")
                && predicted.contains("[0, 0]");
        if (duplicateClaimAgainstKnownValidPattern) {
            return true;
        }

        return expected.equals(predicted);
    }

    private boolean isLikelyTwoSum(String matchedProblemId, String matchedProblemTitle) {
        String id = matchedProblemId == null ? "" : matchedProblemId.trim();
        String title = matchedProblemTitle == null ? "" : matchedProblemTitle.toLowerCase();
        return "1".equals(id) || title.contains("two sum");
    }

    private boolean shouldPromoteToPass(
            String matchedProblemId,
            String matchedProblemTitle,
            double accuracy,
            double confidence,
            int estimatedPassed,
            int estimatedTotal,
            boolean compileLikelyValid,
            List<FailingScenarioResult> failingScenarios
    ) {
        if (!compileLikelyValid || !failingScenarios.isEmpty()) {
            return false;
        }
        if (estimatedTotal >= MIN_STRONG_EVIDENCE_TESTS
                && estimatedPassed == estimatedTotal
                && accuracy >= 99.5
                && confidence >= 96.0) {
            return true;
        }
        return isLikelyTwoSum(matchedProblemId, matchedProblemTitle)
                && estimatedTotal >= MIN_STRONG_EVIDENCE_TESTS
                && estimatedPassed == estimatedTotal
                && accuracy >= 99.5
                && confidence >= 96.0;
    }

    private boolean isStrongPassEvidence(
            double accuracy,
            double confidence,
            int estimatedPassed,
            int estimatedTotal,
            boolean compileLikelyValid
    ) {
        if (!compileLikelyValid) {
            return false;
        }
        if (estimatedTotal <= 0) {
            return false;
        }
        return estimatedTotal >= MIN_STRONG_EVIDENCE_TESTS
                && estimatedPassed == estimatedTotal
                && accuracy >= 99.5
                && confidence >= 97.0;
    }

    private VerdictScore applyStrictEvidenceCalibration(
            String verdict,
            double accuracy,
            double confidence,
            int estimatedPassed,
            int estimatedTotal,
            boolean compileLikelyValid,
            int scenarioCount
    ) {
        String calibratedVerdict = verdict;
        double calibratedAccuracy = accuracy;
        double calibratedConfidence = confidence;
        double passRate = estimatedTotal > 0 ? (estimatedPassed * 1.0) / estimatedTotal : -1.0;

        if (!compileLikelyValid) {
            calibratedVerdict = "FAIL";
            calibratedAccuracy = Math.min(calibratedAccuracy, 70.0);
            calibratedConfidence = Math.min(calibratedConfidence, 72.0);
        }

        if (scenarioCount > 0 && "PASS".equals(calibratedVerdict)) {
            calibratedVerdict = "FAIL";
        }

        if (estimatedTotal <= 0) {
            if ("PASS".equals(calibratedVerdict)) {
                calibratedVerdict = "UNCERTAIN";
            }
            calibratedAccuracy = Math.min(calibratedAccuracy, 88.0);
            calibratedConfidence = Math.min(calibratedConfidence, 72.0);
        }

        if ("PASS".equals(calibratedVerdict)) {
            boolean strongEvidence = estimatedTotal >= MIN_STRONG_EVIDENCE_TESTS
                    && estimatedPassed == estimatedTotal
                    && scenarioCount == 0
                    && calibratedAccuracy >= 99.5
                    && calibratedConfidence >= 97.0;
            if (!strongEvidence) {
                calibratedVerdict = "MAY_PASS";
                calibratedAccuracy = Math.min(calibratedAccuracy, 98.0);
                calibratedConfidence = Math.min(calibratedConfidence, 92.0);
            }
        }

        if ("MAY_PASS".equals(calibratedVerdict)) {
            calibratedAccuracy = Math.min(calibratedAccuracy, 97.0);
            calibratedConfidence = Math.min(calibratedConfidence, 90.0);
            if (estimatedTotal > 0 && passRate < 0.95) {
                calibratedVerdict = "UNCERTAIN";
                calibratedAccuracy = Math.min(calibratedAccuracy, 92.0);
                calibratedConfidence = Math.min(calibratedConfidence, 80.0);
            }
        }

        if ("UNCERTAIN".equals(calibratedVerdict)) {
            calibratedAccuracy = Math.min(calibratedAccuracy, 92.0);
            calibratedConfidence = Math.min(calibratedConfidence, 78.0);
        }

        if ("FAIL".equals(calibratedVerdict)) {
            calibratedAccuracy = Math.min(calibratedAccuracy, 85.0);
            calibratedConfidence = Math.min(calibratedConfidence, 82.0);
        }

        return new VerdictScore(
                calibratedVerdict,
                clampPercent(calibratedAccuracy),
                clampPercent(calibratedConfidence)
        );
    }

    private ScorePair normalizeNonPassScores(
            String verdict,
            double accuracy,
            double confidence,
            int estimatedPassed,
            int estimatedTotal,
            boolean compileLikelyValid,
            int scenarioCount
    ) {
        double normalizedAccuracy = accuracy;
        if (estimatedTotal > 0) {
            normalizedAccuracy = clampPercent((estimatedPassed * 100.0) / estimatedTotal);
        }

        if ("FAIL".equals(verdict)) {
            double scenarioPenalty = Math.min(20.0, scenarioCount * 3.5);
            if (!compileLikelyValid) {
                scenarioPenalty += 15.0;
            }
            normalizedAccuracy = clampPercent(normalizedAccuracy - scenarioPenalty);
        } else if ("MAY_PASS".equals(verdict)) {
            normalizedAccuracy = clampPercent(Math.min(normalizedAccuracy, 98.0));
        }

        double normalizedConfidence = confidence;
        if (estimatedTotal > 0) {
            double passRate = (estimatedPassed * 100.0) / estimatedTotal;
            normalizedConfidence = clampPercent(Math.min(confidence, 50.0 + (passRate * 0.5)));
        }
        if ("FAIL".equals(verdict)) {
            normalizedConfidence = clampPercent(Math.max(35.0, normalizedConfidence - Math.min(25.0, scenarioCount * 4.0)));
        } else if ("MAY_PASS".equals(verdict)) {
            normalizedConfidence = clampPercent(Math.max(70.0, normalizedConfidence));
        }

        return new ScorePair(normalizedAccuracy, normalizedConfidence);
    }

    private double clampPercent(double value) {
        double clamped = Math.max(0, Math.min(100, value));
        return Math.round(clamped * 100.0) / 100.0;
    }

    private Double nullablePercent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            return null;
        }
        return clampPercent(node.asDouble());
    }

    private int clampCount(int value) {
        return Math.max(0, value);
    }

    private String normalizeVerdict(String verdict) {
        if (verdict == null || verdict.isBlank()) {
            return "UNCERTAIN";
        }
        String normalized = verdict.trim().toUpperCase();
        if ("PASS".equals(normalized) || "MAY_PASS".equals(normalized) || "FAIL".equals(normalized) || "UNCERTAIN".equals(normalized)) {
            return normalized;
        }
        return "UNCERTAIN";
    }

    private String stripMarkdownCodeFence(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }

        int firstNewLine = trimmed.indexOf('\n');
        if (firstNewLine < 0) {
            return trimmed;
        }

        String withoutHeader = trimmed.substring(firstNewLine + 1);
        int closingFence = withoutHeader.lastIndexOf("```");
        if (closingFence < 0) {
            return withoutHeader.trim();
        }
        return withoutHeader.substring(0, closingFence).trim();
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "N/A";
        }
        return value.trim();
    }

    private String detectLanguage(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            return "submitted language";
        }
        String code = sourceCode.trim();
        String lower = code.toLowerCase();

        if (lower.contains("#include") || lower.contains("std::") || lower.contains("using namespace std")
                || lower.contains("vector<") || lower.contains("int main(")) {
            return "C++";
        }
        if (lower.contains("import java.") || lower.contains("public class ") || lower.contains("system.out.")
                || lower.contains("public static void main")) {
            return "Java";
        }
        if (lower.contains("def ") || lower.contains("elif ") || lower.contains("print(")
                || lower.contains("if __name__ == \"__main__\":")) {
            return "Python";
        }
        if (lower.contains("function ") || lower.contains("console.log") || lower.contains("const ")
                || lower.contains("let ")) {
            return "JavaScript";
        }
        if (lower.contains("package main") || lower.contains("func ") || lower.contains("fmt.")) {
            return "Go";
        }
        if (lower.contains("using system;") || lower.contains("namespace ") || lower.contains("console.writeline")) {
            return "C#";
        }
        return "submitted language";
    }

    private void applyRateLimit() throws InterruptedException {
        long delayMs = llmProperties.getRequestDelayMs();
        if (delayMs <= 0) {
            return;
        }

        synchronized (this) {
            long now = System.currentTimeMillis();
            long timeSinceLastRequest = now - lastRequestTime;

            if (timeSinceLastRequest < delayMs) {
                long sleepTime = delayMs - timeSinceLastRequest;
                log.debug("Rate limiting: sleeping for {}ms", sleepTime);
                Thread.sleep(sleepTime);
            }

            lastRequestTime = System.currentTimeMillis();
        }
    }

    private record PassChallengeResult(
            boolean hasLikelyFailingCase,
            List<FailingScenarioResult> failingScenarios
    ) {
    }

    private record CodeReviewResult(
            String reviewSummary,
            Double styleScorePercentage,
            List<String> styleFindings,
            List<String> reviewSuggestions
    ) {
    }

    private record ScorePair(
            double accuracy,
            double confidence
    ) {
    }

    private record VerdictScore(
            String verdict,
            double accuracy,
            double confidence
    ) {
    }
}
