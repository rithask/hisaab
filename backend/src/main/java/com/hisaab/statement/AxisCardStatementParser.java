package com.hisaab.statement;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.Institution;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hisaab.statement.StatementText.*;

/** Axis Bank credit card statement: "12 Oct '26  MERCHANT,CITY  ₹ 521.81  Debit". Dates only. */
@Component
@Order(30)
public class AxisCardStatementParser implements StatementParser {
    // The rupee sign is often extracted as "?" or dropped, so accept any single non-digit symbol before the amount.
    private static final Pattern ROW = Pattern.compile("^(\\d{2} [A-Za-z]{3} '\\d{2})\\s+(.+?)\\s+(?:[^\\d\\s-]\\s*)?(" + NUM + ")\\s+(Debit|Credit)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMT = Pattern.compile("(?:[^\\d\\s-]\\s*)?(" + NUM + ")");

    @Override
    public String name() {
        return "axis-card-statement";
    }

    @Override
    public boolean supports(String text) {
        String t = text.toLowerCase();
        return t.contains("credit card number") && t.contains("transaction summary") && (t.contains("axis") || t.contains("my zone"));
    }

    @Override
    public ParsedStatement parse(String text) {
        List<String> all = lines(text);
        String last4 = find(text, "Credit Card Number:\\s*(\\S+)").map(m -> last4(m.group(1))).orElse(null);
        String product = find(text, "Axis Bank ([A-Za-z ]+?) Card Monthly Statement").map(m -> "Axis " + m.group(1).trim()).orElse("Axis credit card");

        BigDecimal totalDue = null, creditLimit = null, opening = null;
        for (int i = 0; i + 1 < all.size(); i++) {
            String l = all.get(i);
            if (l.startsWith("Total Payment Due")) {
                Matcher m = AMT.matcher(all.get(i + 1));
                if (m.find()) totalDue = amount(m.group(1));
            } else if (l.startsWith("Selected Statement Month")) {
                Matcher m = AMT.matcher(all.get(i + 1));
                List<BigDecimal> found = new ArrayList<>();
                while (m.find()) found.add(amount(m.group(1)));
                if (found.size() >= 2) {
                    creditLimit = found.get(0);
                    opening = found.get(1);
                }
            }
        }

        List<StatementRow> rows = new ArrayList<>();
        for (String l : all) {
            Matcher m = ROW.matcher(l);
            if (!m.matches()) continue;
            Direction dir = m.group(4).equalsIgnoreCase("Credit") ? Direction.CREDIT : Direction.DEBIT;
            String desc = m.group(2).trim();
            String merchant = desc.contains(",") ? desc.substring(0, desc.indexOf(',')).trim() : desc;
            boolean payment = dir == Direction.CREDIT && looksLikeCardPayment(desc);
            rows.add(new StatementRow(date(m.group(1), "dd MMM ''yy"), null, desc, payment ? "Card payment" : payee(merchant), null, dir,
                    amount(m.group(3)), null, payment, null));
        }
        rows.sort(Comparator.comparing(StatementRow::date));

        List<ParsedStatement.Check> checks = new ArrayList<>();
        if (totalDue != null && opening != null) {
            BigDecimal computed = opening.add(sum(rows, Direction.DEBIT)).subtract(sum(rows, Direction.CREDIT));
            checks.add(totalCheck("Total due (opening + spends − credits)", computed, totalDue));
        }
        LocalDate from = rows.isEmpty() ? null : rows.getFirst().date();
        LocalDate to = rows.isEmpty() ? null : rows.getLast().date();
        return new ParsedStatement(name(), product + " statement", Institution.AXIS, AccountType.CREDIT_CARD, last4,
                from, to, opening, totalDue, creditLimit, rows, checks);
    }
}
