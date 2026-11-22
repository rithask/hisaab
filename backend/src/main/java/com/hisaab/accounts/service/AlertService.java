package com.hisaab.accounts.service;

import com.hisaab.accounts.dto.AlertResponse;
import com.hisaab.accounts.model.Alert;
import com.hisaab.accounts.repository.AlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.hisaab.common.exception.NotFound.notFound;

/** Balance mismatches: an account's figure differs from what the bank reported. */
@Service
public class AlertService {
    private final AlertRepository alerts;
    private final BalanceService balances;

    public AlertService(AlertRepository alerts, BalanceService balances) {
        this.alerts = alerts;
        this.balances = balances;
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> open() {
        return alerts.findByDismissedFalseOrderByCreatedAtDesc().stream().map(AlertResponse::of).toList();
    }

    @Transactional
    public void dismiss(Long id) {
        alerts.findById(id).orElseThrow(notFound("Alert")).setDismissed(true);
    }

    /** Adds a balance adjustment so the app matches what the bank reported; returns the adjustment's id. */
    @Transactional
    public Long adjust(Long id) {
        Alert alert = alerts.findById(id).orElseThrow(notFound("Alert"));
        return balances.adjust(alert).getId();
    }
}
