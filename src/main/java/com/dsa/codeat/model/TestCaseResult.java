package com.dsa.codeat.model;

public record TestCaseResult(
        int testCaseNumber,
        String input,
        String expectedOutput,
        String actualOutput,
        boolean passed,
        String error,
        long executionTimeMs
) {
}
