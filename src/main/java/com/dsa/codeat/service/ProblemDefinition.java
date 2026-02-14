package com.dsa.codeat.service;

import java.util.List;

public record ProblemDefinition(
        String id,
        String title,
        String description,
        String inputFormat,
        String outputFormat,
        List<TestCase> testCases
) {
}
