package com.dsa.codeat.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ProblemCatalogService {

    private final Map<String, ProblemDefinition> problems = Map.of(
            "binary-search-index",
            new ProblemDefinition(
                    "binary-search-index",
                    "Binary Search Index",
                    "Given a sorted array and target, print index of target or -1.",
                    "Line1: n\nLine2: n space separated integers\nLine3: target",
                    "Single integer index",
                    List.of(
                            new TestCase("5\n1 3 5 7 9\n7\n", "3"),
                            new TestCase("4\n2 4 6 8\n5\n", "-1"),
                            new TestCase("1\n10\n10\n", "0")
                    )
            ),
            "max-subarray-sum",
            new ProblemDefinition(
                    "max-subarray-sum",
                    "Maximum Subarray Sum",
                    "Given an integer array, print the maximum sum of any contiguous subarray.",
                    "Line1: n\nLine2: n space separated integers",
                    "Single integer max sum",
                    List.of(
                            new TestCase("8\n-2 -3 4 -1 -2 1 5 -3\n", "7"),
                            new TestCase("5\n-1 -2 -3 -4 -5\n", "-1"),
                            new TestCase("6\n1 2 3 4 5 6\n", "21")
                    )
            )
    );

    public List<ProblemDefinition> allProblems() {
        return problems.values().stream().toList();
    }

    public Optional<ProblemDefinition> findById(String problemId) {
        return Optional.ofNullable(problems.get(problemId));
    }
}
