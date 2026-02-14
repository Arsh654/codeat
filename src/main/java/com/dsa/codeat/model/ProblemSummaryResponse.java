package com.dsa.codeat.model;

public record ProblemSummaryResponse(
        String id,
        String title,
        String description,
        String inputFormat,
        String outputFormat
) {
}
