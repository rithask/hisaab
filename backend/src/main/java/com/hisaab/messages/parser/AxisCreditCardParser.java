package com.hisaab.messages.parser;

import com.hisaab.common.model.AccountType;
import com.hisaab.common.model.Direction;
import com.hisaab.common.model.Institution;
import com.hisaab.messages.model.EmailContent;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hisaab.messages.parser.ParseUtil.*;

/**
 * Axis Bank credit card spend alerts: "Here's the summary of your Axis Bank Credit Card Transaction", a table of
 * label and value:
 * <pre>
 *   Transaction Amount:         INR 630
 *   Merchant Name:              SOME SHOP
 *   Axis Bank Credit Card No.   XX1234
 *   Date &amp; Time:                05-12-2026, 19:42:07 IST
 *   Available Limit*:           INR …   (across all my Axis cards, so not used)
 *   Total Credit Limit*:        INR …
 * </pre>
 * Each value is found by its label, on the same line or the next, so it doesn't matter how the HTML lays them out.
 * Refunds, reversals and bill payments haven't been seen yet: emails about them are left for review rather than
 * guessed (teaching one there makes the app learn its wording).
 */
@Component
public class AxisCreditCardParser implements BankEmailParser {
    /** Where one value ends because the next label starts (when a table comes out on a single line). */
    private static final Pattern NEXT_LABEL = Pattern.compile(
            "(?i)\\s*(?:Transaction\\s+Amount|Merchant\\s+Name|Axis\\s+Bank\\s+Credit\\s+Card\\s+No|Date\\s*&\\s*Time|Available\\s+Limit|Total\\s+Credit\\s+Limit)\\b.*$");
    private static final Pattern MONEY = Pattern.compile("^" + CURRENCY + "\\s*" + AMOUNT);
    private static final Pattern CARD = Pattern.compile("^[Xx*]*(\\d{4})\\b");
    private static final Pattern WHEN = Pattern.compile("^(\\d{1,2}-\\d{1,2}-\\d{2,4})\\s*,?\\s*(?:(\\d{1,2}:\\d{2}(?::\\d{2})?))?");
    private static final Pattern NOT_A_SPEND = Pattern.compile("(?i)\\b(refund|reversal|reversed|credited|payment received)\\b");

    @Override
    public String name() {
        return "axis-credit-card";
    }

    @Override
    public Institution institution() {
        return Institution.AXIS;
    }

    @Override
    public Optional<ParsedTransaction> parse(EmailContent email) {
        String hay = email.haystack();
        if (!hay.contains("axis bank credit card")) return Optional.empty();
        if (NOT_A_SPEND.matcher(hay).find()) return Optional.empty();

        String text = email.text();
        Matcher money = match(MONEY, labelled(text, "Transaction\\s+Amount"));
        Matcher card = match(CARD, labelled(text, "Axis\\s+Bank\\s+Credit\\s+Card\\s+(?:No\\.?|Number)"));
        if (money == null || card == null) return Optional.empty();

        LocalDate date = null;
        LocalTime time = null;
        Matcher when = match(WHEN, labelled(text, "Date\\s*&\\s*Time"));
        if (when != null) {
            date = dateOrNull(when.group(1));
            time = date == null ? null : timeOrNull(when.group(2)); // unreadable: the email's received time is used
        }
        String merchant = labelled(text, "Merchant\\s+Name");
        if (merchant != null) merchant = clean(NEXT_LABEL.matcher(merchant).replaceFirst(""));

        return Optional.of(new ParsedTransaction(Institution.AXIS, AccountType.CREDIT_CARD, Direction.DEBIT,
                amount(money.group(2)), currency(money.group(1)), card.group(1), date, time, merchant, null, null, false, null));
    }

    private static Matcher match(Pattern p, String value) {
        if (value == null) return null;
        Matcher m = p.matcher(value.trim());
        return m.find() ? m : null;
    }
}
