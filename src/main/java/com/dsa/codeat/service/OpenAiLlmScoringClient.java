package com.dsa.codeat.service;

import com.dsa.codeat.config.LlmProperties;
import com.dsa.codeat.model.AnalyzeRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiLlmScoringClient(LlmProperties llmProperties) {
        this.llmProperties = llmProperties;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    @Override
    public LlmScoreResult score(AnalyzeRequest request) {
        String prompt = buildPrompt(request);

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
            if (shouldPromoteToPass(matchedProblemId, matchedProblemTitle, accuracy, confidence, compileLikelyValid, failingScenarios)) {
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

            return new LlmScoreResult(
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
                    failingScenarios
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to call LLM scorer", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call LLM scorer", e);
        }
    }

    private JsonNode requestCompletionAsJson(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
        String requestBody = objectMapper.writeValueAsString(
                Map.of(
                        "model", llmProperties.getModel(),
                        "temperature", 0.1,
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt)
                        )
                )
        );

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(llmProperties.resolvedApiUrl()))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + llmProperties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("LLM request failed with status " + response.statusCode());
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
        String system = "You are a strict code judge. Return ONLY valid JSON with keys: " +
                "hasLikelyFailingCase (boolean), failingScenarios (array of objects with keys " +
                "inputExample, expectedBehavior, predictedBehavior, reason). " +
                "If any realistic hidden test can fail, set hasLikelyFailingCase=true and provide at least one scenario.";

        String user = "Review this code aggressively for hidden-test failures.\\n" +
                "Current estimated accuracy: " + accuracy + "\\n" +
                "Current feedback: " + safe(feedback) + "\\n" +
                "Code:\\n" + request.sourceCode();

        JsonNode node = requestCompletionAsJson(system, user);
        boolean hasLikelyFailingCase = node.path("hasLikelyFailingCase").asBoolean(false);
        List<FailingScenarioResult> scenarios = toFailingScenarioList(node.path("failingScenarios"));
        return new PassChallengeResult(hasLikelyFailingCase || !scenarios.isEmpty(), scenarios);
    }

    private String systemPrompt() {
        return "You are an expert DSA Java evaluator. Return ONLY valid JSON with keys: " +
                "matchedProblemId, matchedProblemTitle, accuracyPercentage, confidencePercentage, " +
                "leetcodeLikelyVerdict, estimatedPassedTestCases, estimatedTotalTestCases, " +
                "compileLikelyValid, feedback, strengths, improvements, failingScenarios. " +
                "leetcodeLikelyVerdict must be one of PASS, MAY_PASS, FAIL, UNCERTAIN. " +
                "If verdict is FAIL OR accuracyPercentage < 100, failingScenarios must be a non-empty array of " +
                "objects with keys inputExample, expectedBehavior, predictedBehavior, reason.";
    }

    private String buildPrompt(AnalyzeRequest request) {
        StringBuilder prompt = new StringBuilder();
        if (request.problemId() != null && !request.problemId().isBlank()) {
            prompt.append("Problem ID hint: ").append(request.problemId().trim()).append("\\n");
        }
        if (request.problemStatement() != null && !request.problemStatement().isBlank()) {
            prompt.append("Problem statement hint: ").append(request.problemStatement().trim()).append("\\n");
        }
        if (request.className() != null && !request.className().isBlank()) {
            prompt.append("Java class name hint: ").append(request.className().trim()).append("\\n");
        }
        prompt.append("Evaluate this Java DSA solution and estimate correctness percentage.\\n")
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
        if (estimatedTotal > 0 && estimatedPassed == estimatedTotal && failingScenarios.isEmpty()) {
            return "PASS";
        }
        if (estimatedTotal > 0 && passRate >= 0.95 && accuracy >= 95.0 && confidence > 90.0 && failingScenarios.isEmpty()) {
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
            boolean compileLikelyValid,
            List<FailingScenarioResult> failingScenarios
    ) {
        if (!compileLikelyValid || !failingScenarios.isEmpty()) {
            return false;
        }
        if (accuracy >= 99.0) {
            return true;
        }
        return isLikelyTwoSum(matchedProblemId, matchedProblemTitle) && accuracy >= 99.0 && confidence >= 90.0;
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
        return estimatedPassed == estimatedTotal && accuracy >= 99.0 && confidence >= 95.0;
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

    private record PassChallengeResult(
            boolean hasLikelyFailingCase,
            List<FailingScenarioResult> failingScenarios
    ) {
    }

    private record ScorePair(
            double accuracy,
            double confidence
    ) {
    }
}
