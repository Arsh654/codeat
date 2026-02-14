package com.dsa.codeat.service;

public record ExecutionResult(
        String output,
        String error,
        long executionTimeMs,
        boolean timeout
) {
}
