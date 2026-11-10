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
 * Canara Bank "e-Pass Sheet". Each transaction's narration wraps over several lines with the posting date on a
 * middle line, next to the amount and running balance:
 * <pre>
 * UPI/DR/600022223333/RAVI
 * K/YESB/**ABCDE@PTY/NO
 * 02-Nov-2026 30.00 1280.40
 * REMARK//UPIA000000000000000000000000000001
 * A2/01/11/2026 22:03:58 Cheque No.:600022223333
 * </pre>
 * The real transaction time sits inside the narration ("01/11/2026 22:03:58"); when present it wins over the
 * posting date.
 */
@Component
@Order(10)
public class CanaraStatementParser implements StatementParser {
    private static final Pattern ANCHOR = Pattern.compile("^(\\d{2}-[A-Za-z]{3}-\\d{4})\\s*(.*?)\\s*(" + NUM + ")\\s+(" + NUM + ")$");
    private static final Pattern END_OF_TXN = Pattern.compile("No\\.:\\s*\\S+");
    private static final Pattern START_OF_TXN = Pattern.compile("^(UPI|NEFT|IMPS|RTGS|ACH|NACH|ATM|POS|BY|TO|INT|SBINT|CASH|CHQ|MB|IB|BIL|DEP|TRF|ECS|CMS|SMS|CHRG|DEBIT|CREDIT)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern STAMP = Pattern.compile("(\\d{2}/\\d{2}/\\d{4})\\s*(\\d{2}:\\d{2}:\\d{2})");
    private static final Pattern NOISE = Pattern.compile("(?i)^(Date Narration.*|Page \\d+.*|e-Pass Sheet|\\(Report is generated.*|Customer Name.*|Account Number.*|Report Generated.*|Address:.*)$");

    @Override
    public String name() {
        return "canara-statement";
    }

    @Override
    public boolean supports(String text) {
        return text.contains("e-Pass Sheet") || (text.toUpperCase().contains("CANARA") && text.contains("Narration"));
    }

    @Override
    public ParsedStatement parse(String text) {
        List<String> all = lines(text);
        String last4 = find(text, "Account Number:\\s*(\\d+)").map(m -> last4(m.group(1))).orElse(null);
        LocalDate from = find(text, "from (\\d{2}-[A-Za-z]{3}-\\d{4}) to").map(m -> date(m.group(1), "dd-MMM-yyyy")).orElse(null);
        LocalDate to = find(text, "from \\d{2}-[A-Za-z]{3}-\\d{4} to (\\d{2}-[A-Za-z]{3}-\\d{4})").map(m -> date(m.group(1), "dd-MMM-yyyy")).orElse(null);
        BigDecimal opening = find(text, "Opening Balance\\s+(" + NUM + ")").map(m -> amount(m.group(1))).orElse(null);
        BigDecimal closing = find(text, "Closing Balance:\\s*(" + NUM + ")").map(m -> amount(m.group(1))).orElse(null);

        // The body: after the first "Opening Balance n" line, up to the summary block.
        int start = -1, end = all.size();
        for (int i = 0; i < all.size(); i++) {
            String l = all.get(i);
            if (start < 0 && l.matches("(?i)Opening Balance\\s+" + NUM)) start = i + 1;
            else if (start >= 0 && l.matches("(?i)^Opening Balance:.*")) {
                end = i;
                break;
            }
        }
        List<String> body = new ArrayList<>();
        if (start >= 0) for (String l : all.subList(start, end)) if (!NOISE.matcher(l).matches()) body.add(l);

        List<Integer> anchors = new ArrayList<>();
        for (int i = 0; i < body.size(); i++) if (ANCHOR.matcher(body.get(i)).matches()) anchors.add(i);

        // Split the narration lines between two anchors: the first belongs to the earlier transaction up to its
        // "Cheque No.:" line; failing that, a line that starts a new narration (UPI/, NEFT…) begins the next one.
        int[] headStart = new int[anchors.size()];
        int[] tailEnd = new int[anchors.size()];
        headStart[0] = 0;
        for (int k = 0; k < anchors.size(); k++) {
            int a = anchors.get(k);
            int next = k + 1 < anchors.size() ? anchors.get(k + 1) : body.size();
            int split = -1;
            for (int i = a + 1; i < next; i++) {
                if (END_OF_TXN.matcher(body.get(i)).find()) {
                    split = i + 1;
                    break;
                }
            }
            if (split < 0) {
                for (int i = a + 1; i < next; i++) {
                    if (START_OF_TXN.matcher(body.get(i)).matches()) {
                        split = i;
                        break;
                    }
                }
            }
            if (split < 0) split = k + 1 < anchors.size() ? (a + 1 + next) / 2 : next;
            tailEnd[k] = split;
            if (k + 1 < anchors.size()) headStart[k + 1] = split;
        }

        List<StatementRow> rows = new ArrayList<>();
        BigDecimal prev = opening;
        for (int k = 0; k < anchors.size(); k++) {
            int a = anchors.get(k);
            Matcher m = ANCHOR.matcher(body.get(a));
            m.matches();
            StringBuilder narration = new StringBuilder();
            for (int i = headStart[k]; i < a; i++) narration.append(body.get(i)).append(' ');
            narration.append(m.group(2)).append(' ');
            for (int i = a + 1; i < tailEnd[k]; i++) narration.append(body.get(i)).append(' ');
            String n = narration.toString().replaceAll("\\s+", " ").trim();

            BigDecimal amt = amount(m.group(3));
            BigDecimal bal = amount(m.group(4));
            Direction dir = fromBalance(prev, amt, bal);
            String uncertain = null;
            if (dir == null) {
                dir = n.toUpperCase().contains("/CR/") ? Direction.CREDIT : Direction.DEBIT;
                uncertain = "The running balance didn't add up for this row, so check whether it was money in or out.";
            }
            prev = bal;

            LocalDate d = date(m.group(1), "dd-MMM-yyyy");
            LocalTime t = null;
            Matcher st = STAMP.matcher(n);
            if (st.find()) {
                d = date(st.group(1), "dd/MM/yyyy");
                t = LocalTime.parse(st.group(2));
            }
            String core = n.replaceAll("\\s*Cheque\\s*No\\.:.*$", "");
            String ref = find(n, "Cheque\\s*No\\.:\\s*(\\d{6,})").map(x -> x.group(1)).orElse(reference(core));
            rows.add(new StatementRow(d, t, core, payee(core), ref, dir, amt, bal, false, uncertain));
        }

        List<ParsedStatement.Check> checks = new ArrayList<>();
        find(text, "Dr Count:\\s*(\\d+)\\s*Dr Amount:\\s*([0-9.,]+)").ifPresent(x ->
                checks.add(totalCheck("Money out (" + x.group(1) + " rows)", sum(rows, Direction.DEBIT), amount(withPaise(x.group(2))))));
        find(text, "Cr Count:\\s*(\\d+)\\s*Cr Amount:\\s*([0-9.,]+)").ifPresent(x ->
                checks.add(totalCheck("Money in (" + x.group(1) + " rows)", sum(rows, Direction.CREDIT), amount(withPaise(x.group(2))))));
        if (closing != null && !rows.isEmpty()) checks.add(totalCheck("Closing balance", rows.getLast().balance(), closing));

        return new ParsedStatement(name(), "Canara Bank account statement", Institution.CANARA, AccountType.BANK, last4,
                from, to, opening, closing, null, rows, checks);
    }

    private static String withPaise(String s) {
        return s.contains(".") ? s : s + ".00";
    }
}
