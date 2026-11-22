package com.hisaab.messages.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ParseUtil {
    private ParseUtil() {}

    /** Currency prefix: Rs, Rs., INR, ₹ or a 3-letter ISO code. */
    static final String CURRENCY = "(Rs\\.?|INR|₹|[A-Z]{3})";

    /** Amount like 5,222.00 or 6000 or 692.5 */
    static final String AMOUNT = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)";

    static final Pattern BALANCE = Pattern.compile(
            "(?:Avl\\.?|Available|Avail\\.?)\\s*(?:Bal\\.?|Balance|Limit)\\s*(?:is|:)?\\s*(?:Rs\\.?|INR|₹)\\s*([0-9][0-9,]*\\.[0-9]{2})",
            Pattern.CASE_INSENSITIVE);

    public static BigDecimal amount(String s) {
        return new BigDecimal(s.replace(",", "")).setScale(2);
    }

    static String currency(String s) {
        if (s == null) return "INR";
        String c = s.trim().toUpperCase(Locale.ROOT);
        if (c.startsWith("RS") || c.equals("₹")) return "INR";
        return c;
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            fmt("dd MMM, uuuu"), fmt("dd MMM uuuu"), fmt("d MMM, uuuu"), fmt("d MMM uuuu"),
            fmt("dd-MMM-uuuu"), fmt("dd-MMM-uu"),
            fmt("dd/MM/uu"), fmt("dd/MM/uuuu"), fmt("dd-MM-uu"), fmt("dd-MM-uuuu"), fmt("uuuu-MM-dd"));

    private static DateTimeFormatter fmt(String p) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH);
    }

    static LocalDate date(String s) {
        String v = s.trim();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(v, f);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        throw new IllegalArgumentException("Unrecognised date: " + s);
    }

    static LocalTime time(String s) {
        return s == null ? null : LocalTime.parse(s.trim());
    }

    /**
     * The value next to a label in a table-style alert ("Transaction Amount:" then "INR 630"): what follows the
     * label on its line, or else the next non-empty line. The label is a regex; a trailing "*", ":" or "." after it
     * is allowed. What follows the value on the same line is left for the caller to cut. Null when the label isn't there.
     */
    static String labelled(String text, String label) {
        if (text == null) return null;
        // a label starting its line first; else one mid-line (a table that came out on one line)
        Matcher m = Pattern.compile("(?im)^\\s*" + label + "\\s*\\*?\\s*[:.]?[ \\t]*(.*)$").matcher(text);
        if (!m.find()) {
            m = Pattern.compile("(?im)\\b" + label + "\\s*\\*?\\s*[:.]?[ \\t]*(.*)$").matcher(text);
            if (!m.find()) return null;
        }
        String same = m.group(1).trim();
        if (!same.isEmpty()) return same;
        for (String line : text.substring(m.end()).split("\\R")) {
            if (!line.isBlank()) return line.trim();
        }
        return null;
    }

    public static String clean(String s) {
        if (s == null) return null;
        String v = s.replaceAll("\\s+", " ").trim();
        v = v.replaceAll("[.,;:]+$", "").trim();
        return v.isEmpty() ? null : v;
    }

    public record RefAndParty(String reference, String counterparty) {}

    private static final Set<String> RAILS = Set.of("UPI", "IMPS", "NEFT", "RTGS", "ACH", "NACH", "MMT", "INB", "IFT");
    private static final Set<String> UPI_TYPES = Set.of("P2A", "P2M", "P2P", "CR", "DR");

    /**
     * Splits bank narration like {@code UPI/P2A/612345678901/ARJUN RAVI K} into a reference and the
     * counterparty. Falls back to the whole string as counterparty.
     */
    public static RefAndParty splitNarration(String info) {
        if (info == null) return new RefAndParty(null, null);
        String[] parts = info.split("/");
        if (parts.length >= 3 && RAILS.contains(parts[0].trim().toUpperCase(Locale.ROOT))) {
            int refIdx = -1;
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].trim().matches("[A-Za-z0-9-]*[0-9]{6,}[A-Za-z0-9-]*")) {
                    refIdx = i;
                    break;
                }
            }
            String party = null;
            for (int i = (refIdx >= 0 ? refIdx + 1 : 1); i < parts.length; i++) {
                String p = parts[i].trim();
                // a payee has letters: "IFT/CODE-123456/91" has none, only codes
                if (!p.isEmpty() && !UPI_TYPES.contains(p.toUpperCase(Locale.ROOT)) && p.matches(".*[A-Za-z].*")) {
                    party = p;
                    break;
                }
            }
            return new RefAndParty(refIdx >= 0 ? parts[refIdx].trim() : null, clean(party));
        }
        return new RefAndParty(null, clean(info));
    }
}
