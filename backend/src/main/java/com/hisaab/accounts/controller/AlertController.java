package com.hisaab.accounts.controller;

import com.hisaab.accounts.model.Alert;
import com.hisaab.accounts.repository.AlertRepository;
import com.hisaab.accounts.service.BalanceService;
import com.hisaab.common.dto.Dtos.AlertDto;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.hisaab.common.exception.NotFound.notFound;

/** Balance mismatches: an account's figure differs from what the bank reported. */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {
    private final AlertRepository alerts;
    private final BalanceService balances;

    public AlertController(AlertRepository alerts, BalanceService balances) {
        this.alerts = alerts;
        this.balances = balances;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<AlertDto> alerts() {
        return alerts.findByDismissedFalseOrderByCreatedAtDesc().stream().map(AlertDto::of).toList();
    }

    @PostMapping("/{id}/dismiss")
    @Transactional
    public void dismiss(@PathVariable Long id) {
        alerts.findById(id).orElseThrow(notFound("Alert")).setDismissed(true);
    }

    /** Add a balance adjustment so the app matches what the bank reported. */
    @PostMapping("/{id}/adjust")
    @Transactional
    public Map<String, Long> adjust(@PathVariable Long id) {
        Alert a = alerts.findById(id).orElseThrow(notFound("Alert"));
        return Map.of("adjustmentId", balances.adjust(a).getId());
    }
}
