package com.dsa.codeat.model;

public record FailingScenario(
        String inputExample,
        String expectedBehavior,
        String predictedBehavior,
        String reason
) {
}
