package com.hisaab.accounts;

import com.hisaab.common.Audit;
import com.hisaab.common.Enums.AlertType;
import com.hisaab.common.Enums.Direction;
import com.hisaab.common.Enums.TxnSource;
import com.hisaab.transactions.Txn;
import com.hisaab.transactions.TxnRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class BalanceService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BalanceService.class);
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

    /** {@link #current} for many accounts with one query, keyed by account id. */
    public Map<Long, BigDecimal> current(List<Account> accounts) {
        Map<Long, BigDecimal> net = new HashMap<>();
        for (Object[] row : txns.netSinceOpeningByAccount(Direction.CREDIT)) net.put((Long) row[0], (BigDecimal) row[1]);
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Account a : accounts) {
            BigDecimal n = net.getOrDefault(a.getId(), BigDecimal.ZERO);
            out.put(a.getId(), a.isCard() ? a.getOpeningBalance().subtract(n) : a.getOpeningBalance().add(n));
        }
        return out;
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
        // no figures: they're my balances
        log.warn("Balance mismatch on account {} after transaction {}: the bank's balance differs from the app's; a transaction may be missing",
                t.getAccount().getId(), t.getId());
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
        adj.setOccurredAt(ref != null ? ref.getOccurredAt().minusMillis(1) : Instant.now());
        adj.setCounterparty("Balance adjustment");
        adj.setDescription("Auto adjustment to match bank-reported balance");
        adj.setSource(TxnSource.ADJUSTMENT);
        adj.setTransfer(true);
        txns.save(adj);
        alert.setDismissed(true);
        Audit.log("Balance adjusted on account {} to match the bank (adjustment transaction {})", adj.getAccount().getId(), adj.getId());
        return adj;
    }

    static String inr(BigDecimal v) {
        return NumberFormat.getCurrencyInstance(Locale.of("en", "IN")).format(v);
    }
}
