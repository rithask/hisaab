package com.hisaab.messages.parser;

import com.hisaab.common.model.AccountType;
import com.hisaab.common.model.Direction;
import com.hisaab.common.model.Institution;
import com.hisaab.common.util.Money;
import com.hisaab.messages.model.EmailContent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hisaab.messages.parser.ParseUtil.*;

/**
 * ICICI Bank credit card spend alerts ("Transaction alert for your ICICI Bank Credit Card"), one sentence:
 * <pre>
 *   Your ICICI Bank Credit Card XX1234 has been used for a transaction of INR 1,234.00 on Mon d, yyyy at hh:mm:ss.
 *   Info: SOME SHOP.
 * </pre>
 * The time has no am or pm, so it's read as the latest of the two that isn't after the email arrived (an alert
 * comes within minutes); bank times are India time. The limits that follow cover all the cards, so they aren't
 * used. Refunds, reversals and payments haven't been seen yet: they're left for review rather than guessed.
 */
@Component
public class IciciCreditCardParser implements BankEmailParser {
    private static final Pattern SPEND = Pattern.compile(
            "(?i)ICICI\\s+Bank\\s+Credit\\s+Card\\s+[Xx*]*(?<card>\\d{4})\\s+has\\s+been\\s+used\\s+for\\s+a\\s+transaction\\s+of\\s+"
                    + "(?<currency>" + CURRENCY + ")\\s*(?<amount>" + AMOUNT + ")"
                    + "\\s+on\\s+(?<date>[A-Za-z]{3,9}\\.?\\s+\\d{1,2},?\\s+\\d{4})"
                    + "(?:\\s+at\\s+(?<time>\\d{1,2}:\\d{2}(?::\\d{2})?)(?<meridiem>\\s*[AaPp]\\.?[Mm]\\.?)?)?");
    /** "Info: SOME SHOP." up to the end of its line; a merchant may have dots of its own (WWW.SHOP.IN). */
    private static final Pattern INFO = Pattern.compile("(?i)\\bInfo\\s*:\\s*([^\\n]+)");
    private static final Pattern NOT_A_SPEND = Pattern.compile("(?i)\\b(refund|reversal|reversed|credited|payment received)\\b");
    /** An alert can be stamped a little after the email's own Date header. */
    private static final long LEEWAY_SECONDS = 10 * 60;

    @Override
    public String name() {
        return "icici-credit-card";
    }

    @Override
    public Institution institution() {
        return Institution.ICICI;
    }

    @Override
    public Optional<ParsedTransaction> parse(EmailContent email) {
        String hay = email.haystack();
        if (!hay.contains("icici bank credit card")) return Optional.empty();
        if (NOT_A_SPEND.matcher(hay).find()) return Optional.empty();

        String text = email.text();
        Matcher m = SPEND.matcher(text.replaceAll("\\s+", " "));
        if (!m.find()) return Optional.empty();

        LocalDate date = dateOrNull(m.group("date"));
        LocalTime time = date == null ? null : spendTime(m, date, email.receivedAt()); // unreadable: the arrival time is used
        return Optional.of(new ParsedTransaction(Institution.ICICI, AccountType.CREDIT_CARD, Direction.DEBIT,
                amount(m.group("amount")), currency(m.group("currency")), m.group("card"), date, time, merchant(text),
                null, null, false, null));
    }

    /** The time as written: with am or pm when it has one, otherwise read against when the email arrived. */
    private static LocalTime spendTime(Matcher m, LocalDate date, Instant received) {
        String written = m.group("time");
        if (written == null) return null;
        String meridiem = m.group("meridiem");
        return meridiem != null ? timeOrNull(written + " " + meridiem.replace(".", "").trim())
                : clockTime(date, timeOrNull(written), received);
    }

    /** "Info: SOME SHOP." without the full stop that ends the sentence. */
    private static String merchant(String text) {
        Matcher info = INFO.matcher(text);
        return info.find() ? clean(info.group(1).trim().replaceFirst("\\.\\s.*$", "").replaceFirst("\\.$", "")) : null;
    }

    /**
     * A time written on a 12-hour clock without am or pm ("08:14:52"): of the morning and evening readings, the
     * latest one that isn't after the email arrived. A 24-hour time (13:00 and later, or 00:xx) is taken as is, and
     * so is any time when the arrival isn't known.
     */
    static LocalTime clockTime(LocalDate date, LocalTime t, Instant received) {
        if (t == null || received == null || t.getHour() == 0 || t.getHour() > 12) return t;
        LocalTime morning = t.getHour() == 12 ? t.minusHours(12) : t;
        LocalTime evening = morning.plusHours(12);
        Instant latest = received.plusSeconds(LEEWAY_SECONDS);
        boolean eveningFits = !date.atTime(evening).atZone(Money.INDIA).toInstant().isAfter(latest);
        return eveningFits ? evening : morning;
    }
}
