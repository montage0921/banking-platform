package com.group1.banking.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.group1.banking.dto.RiskScoreResponse;
import com.group1.banking.security.CustomUserPrincipal;
import com.group1.banking.service.impl.RiskScoreService;

@RestController
@RequestMapping("/api/risk_score")
public class RiskScoreController {
    private final RiskScoreService riskScoreService;

    public RiskScoreController(RiskScoreService riskScoreService) {
        this.riskScoreService = riskScoreService;
    }

    // admin-only
    @PreAuthorize("hasRole('BANK_ADMINISTRATOR')")
    @PostMapping("customers/{id}")
    public ResponseEntity<RiskScoreResponse> calculateRiskScore(@PathVariable Long id,
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        RiskScoreResponse riskScoreResponse = this.riskScoreService.calculateRiskScore(id);

        return ResponseEntity.ok(riskScoreResponse);
    }

    @PreAuthorize("hasRole('BANK_ADMINISTRATOR')")
    @GetMapping("customers/{id}/history")
    public ResponseEntity<List<RiskScoreResponse>> getRiskScoreHistory(@PathVariable Long id,
            @AuthenticationPrincipal CustomUserPrincipal principal) {

        List<RiskScoreResponse> scoreHistory = this.riskScoreService.getRiskScoreHistory(id);
        return ResponseEntity.ok(scoreHistory);
    }

    @PreAuthorize("hasRole('BANK_ADMINISTRATOR')")
    @GetMapping("customers/{id}")
    public ResponseEntity<RiskScoreResponse> getRiskScore(@PathVariable Long id,
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        RiskScoreResponse riskScoreResponse = this.riskScoreService.getRiskScoreById(id, principal);
        return ResponseEntity.ok(riskScoreResponse);
    }

}
