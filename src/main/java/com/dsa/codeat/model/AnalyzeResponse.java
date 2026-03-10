package com.dsa.codeat.model;

import java.util.List;

public record AnalyzeResponse(
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
        List<FailingScenario> failingScenarios,
        String reviewSummary,
        Double styleScorePercentage,
        List<String> styleFindings,
        List<String> reviewSuggestions,
        String modelUsed
) {
}
