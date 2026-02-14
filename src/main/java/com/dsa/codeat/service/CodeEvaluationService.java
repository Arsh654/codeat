package com.dsa.codeat.service;

import com.dsa.codeat.config.LlmProperties;
import com.dsa.codeat.model.AnalyzeRequest;
import com.dsa.codeat.model.AnalyzeResponse;
import com.dsa.codeat.model.FailingScenario;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CodeEvaluationService {

    private final LlmScoringClient llmScoringClient;
    private final String modelUsed;

    @Autowired
    public CodeEvaluationService(LlmScoringClient llmScoringClient, LlmProperties llmProperties) {
        this(llmScoringClient, llmProperties.getModel());
    }

    private CodeEvaluationService(LlmScoringClient llmScoringClient, String modelUsed) {
        this.llmScoringClient = llmScoringClient;
        this.modelUsed = modelUsed;
    }

    static CodeEvaluationService forTesting(LlmScoringClient llmScoringClient, String modelUsed) {
        return new CodeEvaluationService(llmScoringClient, modelUsed);
    }

    public AnalyzeResponse analyze(AnalyzeRequest request) {
        LlmScoreResult result = llmScoringClient.score(request);

        return new AnalyzeResponse(
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
                modelUsed
        );
    }

    private String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
