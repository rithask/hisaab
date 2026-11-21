package com.hisaab.messages.reading;

import com.hisaab.accounts.BalanceService;
import com.hisaab.common.Enums.Direction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Step 6, SMS: the balance a bank prints after a transaction is arithmetic, not wording, so it outranks the reader.
 * If the figure only makes sense with the opposite direction, the direction is flipped; when it fits exactly one
 * direction it also confirms the reading. Left alone when it agrees, is absent, or fits neither way (e.g. an
 * earlier transaction is missing).
 */
@Component
class CheckBalance implements ReadingStep {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private final BalanceService balances;

    CheckBalance(BalanceService balances) {
        this.balances = balances;
    }

    @Override
    public void apply(MessageReading r) {
        var p = r.parsed;
        if (!r.sms() || p.balance() == null || r.account.isCard() || !"INR".equals(p.currency())) return;
        BigDecimal now = balances.current(r.account);
        boolean asDebit = now.subtract(p.amount()).subtract(p.balance()).abs().compareTo(TOLERANCE) <= 0;
        boolean asCredit = now.add(p.amount()).subtract(p.balance()).abs().compareTo(TOLERANCE) <= 0;
        if (asDebit == asCredit) return;
        r.balanceAddsUp = true;
        Direction right = asDebit ? Direction.DEBIT : Direction.CREDIT;
        if (right != p.direction()) {
            r.parsed = p.withDirection(right);
            r.notes.append("Direction corrected using the balance in the message.");
        }
    }
}
