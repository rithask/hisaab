package com.hisaab.statement;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.Institution;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hisaab.statement.StatementText.*;

/**
 * Axis Bank savings account statement: "dd-MM-yyyy  narration  amount  balance", dates only, long narrations
 * continue on the next line. Withdrawal vs deposit is read from the running balance.
 */
@Component
@Order(20)
public class AxisBankStatementParser implements StatementParser {
    private static final Pattern ROW = Pattern.compile("^(\\d{2}-\\d{2}-\\d{4})\\s+(.+?)\\s+(" + NUM + ")\\s+(" + NUM + ")$");
    private static final Pattern NOISE = Pattern.compile("(?i)^(Date Transaction Details.*|No\\.|Page \\d+ of \\d+|Statement for Account.*)$");

    @Override
    public String name() {
        return "axis-bank-statement";
    }

    @Override
    public boolean supports(String text) {
        return text.contains("Statement for Account No") && text.contains("Withdrawal") && text.toLowerCase().contains("axis");
    }

    @Override
    public ParsedStatement parse(String text) {
        List<String> all = lines(text);
        String last4 = find(text, "Statement for Account No\\.?\\s*(\\S+)").map(m -> last4(m.group(1))).orElse(null);
        LocalDate from = find(text, "period from (\\d{2}-\\d{2}-\\d{4}) to").map(m -> date(m.group(1), "dd-MM-yyyy")).orElse(null);
        LocalDate to = find(text, "period from \\d{2}-\\d{2}-\\d{4} to (\\d{2}-\\d{2}-\\d{4})").map(m -> date(m.group(1), "dd-MM-yyyy")).orElse(null);
        BigDecimal opening = find(text, "Opening Balance\\s+(" + NUM + ")").map(m -> amount(m.group(1))).orElse(null);
        BigDecimal closing = find(text, "Closing Balance\\s+(" + NUM + ")").map(m -> amount(m.group(1))).orElse(null);

        List<StatementRow> rows = new ArrayList<>();
        List<String[]> raw = new ArrayList<>(); // date, narration, amount, balance
        boolean in = false;
        for (String l : all) {
            if (!in) {
                if (l.matches("(?i)Opening Balance\\s+" + NUM)) in = true;
                continue;
            }
            if (l.matches("(?i)^Closing Balance\\s+.*")) break;
            if (NOISE.matcher(l).matches()) continue;
            Matcher m = ROW.matcher(l);
            if (m.matches()) raw.add(new String[]{m.group(1), m.group(2), m.group(3), m.group(4)});
            else if (!raw.isEmpty()) raw.getLast()[1] = raw.getLast()[1] + " " + l; // narration continues
        }

        BigDecimal prev = opening;
        for (String[] r : raw) {
            BigDecimal amt = amount(r[2]);
            BigDecimal bal = amount(r[3]);
            Direction dir = fromBalance(prev, amt, bal);
            String uncertain = null;
            if (dir == null) {
                dir = Direction.DEBIT;
                uncertain = "The running balance didn't add up for this row, so check whether it was money in or out.";
            }
            prev = bal;
            String n = r[1].trim();
            String party = n.matches("(?i)^SB:.*INT\\.?PD.*") ? "Interest" : payee(n);
            rows.add(new StatementRow(date(r[0], "dd-MM-yyyy"), null, n, party, reference(n), dir, amt, bal, false, uncertain));
        }

        List<ParsedStatement.Check> checks = new ArrayList<>();
        find(text, "Total\\s+(" + NUM + ")\\s+(" + NUM + ")").ifPresent(x -> {
            checks.add(totalCheck("Withdrawals", sum(rows, Direction.DEBIT), amount(x.group(1))));
            checks.add(totalCheck("Deposits", sum(rows, Direction.CREDIT), amount(x.group(2))));
        });
        if (closing != null && !rows.isEmpty()) checks.add(totalCheck("Closing balance", rows.getLast().balance(), closing));

        return new ParsedStatement(name(), "Axis Bank account statement", Institution.AXIS, AccountType.BANK, last4,
                from, to, opening, closing, null, rows, checks);
    }
}
