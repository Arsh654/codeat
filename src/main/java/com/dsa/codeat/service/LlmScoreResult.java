package com.dsa.codeat.service;

import java.util.List;

public record LlmScoreResult(
        String matchedProblemId,
        String matchedProblemTitle,
        double accuracyPercentage,
        double confidencePercentage,
        String leetcodeLikelyVerdict,
        int estimatedPassedTestCases,
        int estimatedTotalTestCases,
        boolean compileLikelyValid,
        String feedback,
        List<String> strengths,
        List<String> improvements,
        List<FailingScenarioResult> failingScenarios
) {
}
