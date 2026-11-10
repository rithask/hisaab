package com.hisaab.statement;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.Institution;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hisaab.statement.StatementText.*;

/**
 * HDFC Bank credit card statement: "01/10/2026| 16:38  Swiggy InstamartBENGALURU  ₹ 263.00", with "+" marking
 * credits. A time of 00:00 is HDFC's placeholder for "no time" (cashback, refunds), so it's treated as date-only.
 */
@Component
@Order(40)
public class HdfcCardStatementParser implements StatementParser {
    private static final Pattern ROW = Pattern.compile(
            "^(\\d{2}/\\d{2}/\\d{4})\\s*\\|?\\s*(\\d{2}:\\d{2})\\s+(.+?)\\s+(\\+\\s*)?(?:[^\\d\\s+]\\s*)?(" + NUM + ")(?:\\s+\\S)?$");
    // "C5,461.18 C7,080.71 + C6,841.65 + C0.00 = C5,222.00": previous dues, payments/credits, purchases, charges, total
    private static final Pattern SUMMARY = Pattern.compile(
            "^\\S?\\s*(" + NUM + ")\\s+\\S?\\s*(" + NUM + ")\\s*\\+\\s*\\S?\\s*(" + NUM + ")\\s*\\+\\s*\\S?\\s*(" + NUM + ")\\s*=\\s*\\S?\\s*(" + NUM + ")$");

    @Override
    public String name() {
        return "hdfc-card-statement";
    }

    @Override
    public boolean supports(String text) {
        return text.contains("HDFC Bank Credit Card") && text.contains("DATE & TIME");
    }

    @Override
    public ParsedStatement parse(String text) {
        List<String> all = lines(text);
        String last4 = find(text, "Credit Card No\\.?\\s*(\\S+)").map(m -> last4(m.group(1))).orElse(null);
        String product = find(text, "^\\s*(.+?) Bank Credit Card Statement").map(m -> m.group(1).trim() + " Bank credit card").orElse("HDFC credit card");
        LocalDate from = find(text, "Billing Period\\s+(\\d{1,2} [A-Za-z]{3}, \\d{4})").map(m -> date(m.group(1), "d MMM, yyyy")).orElse(null);
        LocalDate to = find(text, "Billing Period\\s+\\d{1,2} [A-Za-z]{3}, \\d{4}\\s*-\\s*(\\d{1,2} [A-Za-z]{3}, \\d{4})").map(m -> date(m.group(1), "d MMM, yyyy")).orElse(null);

        BigDecimal previous = null, credits = null, debits = null, charges = null, totalDue = null;
        for (String l : all) {
            Matcher m = SUMMARY.matcher(l);
            if (m.matches()) {
                previous = amount(m.group(1));
                credits = amount(m.group(2));
                debits = amount(m.group(3));
                charges = amount(m.group(4));
                totalDue = amount(m.group(5));
                break;
            }
        }

        List<StatementRow> rows = new ArrayList<>();
        for (String l : all) {
            Matcher m = ROW.matcher(l);
            if (!m.matches()) continue;
            Direction dir = m.group(4) != null ? Direction.CREDIT : Direction.DEBIT;
            LocalTime time = m.group(2).equals("00:00") ? null : LocalTime.parse(m.group(2));
            String desc = m.group(3).trim();
            boolean payment = dir == Direction.CREDIT && looksLikeCardPayment(desc);
            rows.add(new StatementRow(date(m.group(1), "dd/MM/yyyy"), time, desc, payment ? "Card payment" : payee(desc), reference(desc), dir,
                    amount(m.group(5)), null, payment, null));
        }

        List<ParsedStatement.Check> checks = new ArrayList<>();
        if (debits != null) {
            checks.add(totalCheck("Purchases and debits", sum(rows, Direction.DEBIT), debits.add(charges)));
            checks.add(totalCheck("Payments and credits", sum(rows, Direction.CREDIT), credits));
        }
        return new ParsedStatement(name(), product + " statement", Institution.HDFC, AccountType.CREDIT_CARD, last4,
                from, to, previous, totalDue, null, rows, checks);
    }
}
