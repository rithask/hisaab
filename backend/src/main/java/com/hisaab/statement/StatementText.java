package com.hisaab.statement;

import com.hisaab.domain.Enums.Direction;
import com.hisaab.mail.parser.ParseUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small helpers shared by the statement parsers. */
final class StatementText {
    private StatementText() {}

    static final String NUM = "-?[0-9][0-9,]*\\.[0-9]{2}";

    static List<String> lines(String text) {
        return Arrays.stream(text.split("\\R")).map(l -> l.replace(' ', ' ').replaceAll("\\s+", " ").trim())
                .filter(l -> !l.isEmpty()).toList();
    }

    static BigDecimal amount(String s) {
        return ParseUtil.amount(s.replace("-", "")).multiply(s.trim().startsWith("-") ? BigDecimal.ONE.negate() : BigDecimal.ONE);
    }

    static Optional<Matcher> find(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? Optional.of(m) : Optional.empty();
    }

    static LocalDate date(String s, String pattern) {
        DateTimeFormatter f = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH);
        return LocalDate.parse(s.trim(), f);
    }

    static String last4(String accountNo) {
        String d = accountNo.replaceAll("[^0-9]", "");
        return d.length() >= 4 ? d.substring(d.length() - 4) : null;
    }

    /**
     * Direction from the running balance: the balance arithmetic is exact, so it beats column positions or words.
     * Returns null when the amount fits neither way (the caller then falls back and flags the row).
     */
    static Direction fromBalance(BigDecimal previous, BigDecimal amount, BigDecimal balance) {
        if (previous == null || balance == null) return null;
        if (previous.subtract(amount).compareTo(balance) == 0) return Direction.DEBIT;
        if (previous.add(amount).compareTo(balance) == 0) return Direction.CREDIT;
        return null;
    }

    static BigDecimal sum(List<StatementRow> rows, Direction d) {
        return rows.stream().filter(r -> r.direction() == d).map(StatementRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static ParsedStatement.Check totalCheck(String label, BigDecimal parsed, BigDecimal printed) {
        boolean ok = parsed.compareTo(printed) == 0;
        return new ParsedStatement.Check(label, ok, ok ? "₹" + printed.toPlainString() : "read ₹" + parsed.toPlainString() + ", statement says ₹" + printed.toPlainString());
    }

    private static final Pattern PROCESSOR_PREFIX = Pattern.compile("^(?:RSP|PAY|PYU|RAZ|SQ|IND|CCAVE|BDK)\\*\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern GLUED_CITY = Pattern.compile(
            "(?i)(?<=[a-z0-9.)])\\s?(bengaluru|bangalore|gurgaon|gurugram|mumbai|new delhi|delhi|chennai|hyderabad|kolkata|pune|noida|kochi|cochin|ernakulam|kozhikode|calicut|thrissur|trivandrum|thiruvananthapuram|coimbatore|ahmedabad|jaipur|singapore|san francisco|dublin)$");

    /**
     * A readable payee from a statement description: UPI/IMPS narrations give the counterparty field; card lines
     * lose the town the bank glued on ("Swiggy InstamartBENGALURU") and card-processor prefixes ("RSP*").
     */
    static String payee(String description) {
        if (description == null) return null;
        String d = description.trim();
        ParseUtil.RefAndParty rp = ParseUtil.splitNarration(d);
        if (rp.counterparty() != null && d.matches("(?i)^(UPI|IMPS|NEFT|RTGS|ACH|NACH|MMT|IFT)/.*")) return rp.counterparty();
        d = d.replaceAll("\\(Ref#?\\s*[A-Za-z0-9]+\\)", "").trim();
        d = d.replaceAll("(?i)^(UPI TRANSFER TO|TRANSFER TO|TRANSFER FROM|TRF TO|TRF FROM|BY TRANSFER|TO TRANSFER)\\s+", "");
        d = d.replaceAll("\\s*\\([A-Z0-9]{4,}\\)$", "");
        d = PROCESSOR_PREFIX.matcher(d).replaceFirst("");
        String before = d;
        d = GLUED_CITY.matcher(d).replaceFirst("").trim();
        if (d.length() < 2) d = before;
        return ParseUtil.clean(d);
    }

    static String reference(String description) {
        return find(description, "\\(Ref#?\\s*([A-Za-z0-9]{6,})\\)|\\((\\d{9,})\\)").map(m -> m.group(1) != null ? m.group(1) : m.group(2))
                .or(() -> Optional.ofNullable(ParseUtil.splitNarration(description).reference()))
                .orElse(null);
    }

    static boolean looksLikeCardPayment(String description) {
        return description.matches("(?i).*\\b(PYMT|PAYMENT|BPPY|BBPS|NEFT|IMPS|UPI|AUTOPAY|THANK YOU)\\b.*");
    }
}
