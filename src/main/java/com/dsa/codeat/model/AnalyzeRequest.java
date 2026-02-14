package com.dsa.codeat.model;

import jakarta.validation.constraints.NotBlank;

public record AnalyzeRequest(
        String problemId,
        String problemStatement,
        @NotBlank String sourceCode,
        String className
) {
}
