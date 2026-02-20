package com.dsa.codeat.service;

import com.dsa.codeat.config.LlmProperties;
import com.dsa.codeat.model.AnalyzeRequest;
import com.dsa.codeat.model.AnalyzeResponse;
import com.dsa.codeat.model.FailingScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CodeEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(CodeEvaluationService.class);

    private final LlmScoringClient llmScoringClient;

    @Autowired
    public CodeEvaluationService(LlmScoringClient llmScoringClient) {
        this.llmScoringClient = llmScoringClient;
    }

    static CodeEvaluationService forTesting(LlmScoringClient llmScoringClient) {
        return new CodeEvaluationService(llmScoringClient);
    }

    public AnalyzeResponse analyze(AnalyzeRequest request) {
        log.info("Analyze request received. problemIdHint={}, classNameHint={}, codeLength={}",
                request.problemId(), request.className(),
                request.sourceCode() != null ? request.sourceCode().length() : 0);

        try {
            validateCodeIsNotSkeleton(request.sourceCode());
        } catch (IllegalArgumentException e) {
            log.warn("Code validation failed: {}", e.getMessage());
            throw e;
        }

        LlmScoreResult result = llmScoringClient.score(request);

        AnalyzeResponse response = new AnalyzeResponse(
                emptyToNull(result.matchedProblemId()),
                emptyToNull(result.matchedProblemTitle()),
                result.accuracyPercentage(),
                result.confidencePercentage(),
                result.leetcodeLikelyVerdict(),
                result.estimatedPassedTestCases(),
                result.estimatedTotalTestCases(),
                result.compileLikelyValid(),
                result.feedback(),
                result.strengths(),
                result.improvements(),
                result.failingScenarios().stream()
                        .map(s -> new FailingScenario(
                                s.inputExample(),
                                s.expectedBehavior(),
                                s.predictedBehavior(),
                                s.reason()
                        ))
                        .toList(),
                result.modelUsed()
        );

        log.info("Analyze result. matchedProblemId={}, verdict={}, accuracy={}, confidence={}",
                response.matchedProblemId(),
                response.leetcodeLikelyVerdict(),
                response.accuracyPercentage(),
                response.confidencePercentage());
        return response;
    }

    private String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void validateCodeIsNotSkeleton(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("Source code cannot be empty");
        }

        String trimmed = sourceCode.trim();

        // Very minimal check - just ensure it's not tiny
        if (trimmed.length() < 25) {
            throw new IllegalArgumentException("Code is too short to analyze");
        }

        // Only reject obvious empty skeletons
        if (isObviouslyEmptySkeleton(trimmed)) {
            throw new IllegalArgumentException("Code appears to be an empty template without implementation");
        }
    }

    private boolean isObviouslyEmptySkeleton(String sourceCode) {
        // Remove all whitespace and comments for analysis
        String compressed = sourceCode
                .replaceAll("//[^\\r\\n]*", "")
                .replaceAll("/\\*[\\s\\S]*?\\*/", "")
                .replaceAll("\\s+", "");

        // Check for pattern: class Name { method(...) { } }
        // This is a very simple heuristic - only catches the most obvious empty cases

        // Must have a class
        if (!compressed.contains("class")) {
            return false;
        }

        // Count braces
        int openBraces = 0;
        int closeBraces = 0;
        for (char c : compressed.toCharArray()) {
            if (c == '{') openBraces++;
            if (c == '}') closeBraces++;
        }

        // If there are only 2 pairs of braces (class + one method), and code is short, likely empty
        if (openBraces == 2 && closeBraces == 2 && compressed.length() < 100) {
            // Check if it contains empty method body: ){}
            return compressed.contains("){}");
        }

        return false;
    }
}
