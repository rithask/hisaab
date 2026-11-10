package com.hisaab.service;

import com.hisaab.domain.Account;
import com.hisaab.domain.Alert;
import com.hisaab.domain.Enums.AlertType;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.TxnSource;
import com.hisaab.domain.Txn;
import com.hisaab.repo.AlertRepo;
import com.hisaab.repo.TxnRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

@Service
public class BalanceService {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final TxnRepo txns;
    private final AlertRepo alerts;

    public BalanceService(TxnRepo txns, AlertRepo alerts) {
        this.txns = txns;
        this.alerts = alerts;
    }

    /** Bank/cash: current balance. Credit card: current outstanding (positive = I owe the bank). */
    public BigDecimal current(Account a) {
        BigDecimal net = txns.netSince(a, a.getOpeningDate());
        return a.isCard() ? a.getOpeningBalance().subtract(net) : a.getOpeningBalance().add(net);
    }

    /** Balance the app expects right after the given transaction. */
    public BigDecimal expectedAfter(Txn t) {
        Account a = t.getAccount();
        return a.getOpeningBalance().add(txns.netUpTo(a, a.getOpeningDate(), t.getOccurredAt(), t.getId()));
    }

    /** Compare the balance reported in a bank email with what the app calculated; raise an alert on mismatch. */
    @Transactional
    public void verifyReportedBalance(Txn t) {
        if (t.getReportedBalance() == null || t.getAccount().isCard()) return;
        if (t.getOccurredAt().isBefore(t.getAccount().getOpeningDate())) return;
        BigDecimal expected = expectedAfter(t);
        BigDecimal diff = t.getReportedBalance().subtract(expected);
        if (diff.abs().compareTo(TOLERANCE) < 0) return;

        Alert alert = new Alert();
        alert.setType(AlertType.BALANCE_MISMATCH);
        alert.setAccount(t.getAccount());
        alert.setTxn(t);
        alert.setExpected(expected);
        alert.setActual(t.getReportedBalance());
        alert.setMessage("%s: bank reports %s but the app calculated %s (difference %s). A transaction may be missing."
                .formatted(t.getAccount().getName(), inr(t.getReportedBalance()), inr(expected), inr(diff)));
        alerts.save(alert);
    }

    /** Resolve a mismatch by adding an adjustment entry so the app matches the bank. */
    @Transactional
    public Txn adjust(Alert alert) {
        Txn ref = alert.getTxn();
        BigDecimal diff = alert.getActual().subtract(alert.getExpected());
        Txn adj = new Txn();
        adj.setAccount(alert.getAccount());
        adj.setDirection(diff.signum() > 0 ? Direction.CREDIT : Direction.DEBIT);
        adj.setAmount(diff.abs());
        // place it just before the reference transaction so the reported balance lines up
        adj.setOccurredAt(ref != null ? ref.getOccurredAt().minusMillis(1) : java.time.Instant.now());
        adj.setCounterparty("Balance adjustment");
        adj.setDescription("Auto adjustment to match bank-reported balance");
        adj.setSource(TxnSource.ADJUSTMENT);
        adj.setTransfer(true);
        txns.save(adj);
        alert.setDismissed(true);
        return adj;
    }

    static String inr(BigDecimal v) {
        return NumberFormat.getCurrencyInstance(Locale.of("en", "IN")).format(v);
    }
}
