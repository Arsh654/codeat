package com.dsa.codeat.controller;

import com.dsa.codeat.model.AnalyzeRequest;
import com.dsa.codeat.model.AnalyzeResponse;
import com.dsa.codeat.model.ProblemSummaryResponse;
import com.dsa.codeat.service.CodeEvaluationService;
import com.dsa.codeat.service.ProblemCatalogService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Validated
public class EvaluationController {

    private final ProblemCatalogService problemCatalogService;
    private final CodeEvaluationService codeEvaluationService;

    public EvaluationController(ProblemCatalogService problemCatalogService, CodeEvaluationService codeEvaluationService) {
        this.problemCatalogService = problemCatalogService;
        this.codeEvaluationService = codeEvaluationService;
    }

    @GetMapping("/problems")
    public List<ProblemSummaryResponse> listProblems() {
        return problemCatalogService.allProblems().stream()
                .map(problem -> new ProblemSummaryResponse(
                        problem.id(),
                        problem.title(),
                        problem.description(),
                        problem.inputFormat(),
                        problem.outputFormat()
                ))
                .toList();
    }

    @PostMapping("/analyze")
    public ResponseEntity<AnalyzeResponse> analyze(@Valid @RequestBody AnalyzeRequest request) {
        return ResponseEntity.ok(codeEvaluationService.analyze(request));
    }
}
