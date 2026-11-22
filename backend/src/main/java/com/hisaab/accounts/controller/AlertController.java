package com.hisaab.accounts.controller;

import com.hisaab.accounts.dto.AlertResponse;
import com.hisaab.accounts.service.AlertService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Balance mismatches: an account's figure differs from what the bank reported. */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {
    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public List<AlertResponse> alerts() {
        return alerts.open();
    }

    @PostMapping("/{id}/dismiss")
    public void dismiss(@PathVariable Long id) {
        alerts.dismiss(id);
    }

    /** Add a balance adjustment so the app matches what the bank reported. */
    @PostMapping("/{id}/adjust")
    public Map<String, Long> adjust(@PathVariable Long id) {
        return Map.of("adjustmentId", alerts.adjust(id));
    }
}
