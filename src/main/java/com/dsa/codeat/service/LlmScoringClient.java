package com.dsa.codeat.service;

import com.dsa.codeat.model.AnalyzeRequest;

public interface LlmScoringClient {
    LlmScoreResult score(AnalyzeRequest request);
}
