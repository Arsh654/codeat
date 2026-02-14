package com.dsa.codeat.service;

import com.dsa.codeat.model.AnalyzeRequest;
import com.dsa.codeat.model.AnalyzeResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvaluationServiceTest {

    private final ProblemCatalogService problemCatalogService = new ProblemCatalogService();

    @Test
    void shouldListProblems() {
        assertThat(problemCatalogService.allProblems()).hasSize(2);
    }

    @Test
    void shouldReturnLlmScore() {
        LlmScoringClient llmScoringClient = request -> new LlmScoreResult(
                "binary-search-index",
                "Binary Search Index",
                84.5,
                91.0,
                "FAIL",
                17,
                35,
                true,
                "Good approach but edge handling is incomplete.",
                List.of("Uses binary search", "Time complexity is O(log n)"),
                List.of("Handle duplicate values", "Add empty input guard"),
                List.of(new FailingScenarioResult(
                        "nums=[3,3], target=6",
                        "Return indices [0,1] (order may vary).",
                        "May reuse the same index or miss valid pair due to index lookup logic.",
                        "Index recovery after sorting can mis-handle duplicates."
                ))
        );

        CodeEvaluationService codeEvaluationService = CodeEvaluationService.forTesting(llmScoringClient, "test-model");

        AnalyzeResponse response = codeEvaluationService.analyze(new AnalyzeRequest(
                null,
                null,
                "public class Main { public static void main(String[] args) {} }",
                "Main"
        ));

        assertThat(response.accuracyPercentage()).isEqualTo(84.5);
        assertThat(response.confidencePercentage()).isEqualTo(91.0);
        assertThat(response.leetcodeLikelyVerdict()).isEqualTo("FAIL");
        assertThat(response.estimatedPassedTestCases()).isEqualTo(17);
        assertThat(response.estimatedTotalTestCases()).isEqualTo(35);
        assertThat(response.modelUsed()).isEqualTo("test-model");
        assertThat(response.strengths()).hasSize(2);
        assertThat(response.failingScenarios()).isNotEmpty();
        assertThat(response.failingScenarios().get(0).inputExample()).contains("nums=[3,3]");
    }

    @Test
    void shouldExposeLlmFailure() {
        LlmScoringClient llmScoringClient = request -> {
            throw new IllegalStateException("LLM client is not configured.");
        };
        CodeEvaluationService codeEvaluationService = CodeEvaluationService.forTesting(llmScoringClient, "test-model");

        try {
            codeEvaluationService.analyze(new AnalyzeRequest(
                    null,
                    null,
                    "public class Main {}",
                    "Main"
            ));
        } catch (IllegalStateException ex) {
            assertThat(ex.getMessage()).contains("not configured");
            return;
        }

        throw new AssertionError("Expected IllegalStateException");
    }
}
