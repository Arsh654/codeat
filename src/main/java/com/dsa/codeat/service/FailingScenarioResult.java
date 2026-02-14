package com.dsa.codeat.service;

public record FailingScenarioResult(
        String inputExample,
        String expectedBehavior,
        String predictedBehavior,
        String reason
) {
}
