package com.hisaab.mail.parser;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.Institution;
import com.hisaab.mail.EmailContent;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hisaab.mail.parser.ParseUtil.*;

/**
 * HDFC Bank credit card alerts.
 * <p>Verified format: "Rs. 692.00 has been debited from your HDFC Bank Credit Card ending 6619 towards
 * Swiggy Instamart on 06 Nov, 2026 at 23:05:44."
 * <p>The older "Thank you for using your HDFC Bank Credit Card ending 1234 for Rs 500.00 at X on 01-01-2024
 * 10:00:00" format and payment/credit variants are handled on a best-effort basis.
 */
@Component
public class HdfcCreditCardParser implements BankEmailParser {

    private static final Pattern CURRENT = Pattern.compile(
            CURRENCY + "\\s*" + AMOUNT + "\\s+(?i:has\\s+been\\s+(debited|credited))\\s+(?i:from|to)\\s+your\\s+"
                    + "(?i:HDFC\\s+Bank\\s+Credit\\s+Card\\s+ending)\\s+(\\d{4})"
                    + "(?:\\s+(?i:towards|by|from|at|for)\\s+(.+?))?"
                    + "\\s+(?i:on)\\s+(\\d{1,2}\\s+[A-Za-z]{3},?\\s+\\d{4})"
                    + "(?:\\s+(?i:at)\\s+(\\d{2}:\\d{2}:\\d{2}))?");

    private static final Pattern LEGACY = Pattern.compile(
            "(?i:HDFC\\s+Bank\\s+Credit\\s+Card\\s+ending)\\s+(\\d{4})\\s+(?i:for)\\s+" + CURRENCY + "\\s*" + AMOUNT
                    + "\\s+(?i:at)\\s+(.+?)\\s+(?i:on)\\s+(\\d{2}-\\d{2}-\\d{4})(?:\\s+(\\d{2}:\\d{2}:\\d{2}))?");

    private static final Pattern PAYMENT = Pattern.compile(
            "(?i)payment\\s+of\\s+(Rs\\.?|INR|₹)\\s*" + AMOUNT + ".{0,120}?(?:Credit\\s+Card\\s+ending|Card\\s+no\\.?\\s*X*)\\s*(\\d{4})");

    @Override
    public String name() {
        return "hdfc-credit-card";
    }

    @Override
    public Optional<ParsedTransaction> parse(EmailContent email) {
        String text = email.flat();
        if (!email.haystack().contains("hdfc")) return Optional.empty();

        Matcher m = CURRENT.matcher(text);
        if (m.find()) {
            Direction dir = m.group(3).equalsIgnoreCase("debited") ? Direction.DEBIT : Direction.CREDIT;
            boolean payment = dir == Direction.CREDIT && email.haystack().contains("payment");
            return Optional.of(new ParsedTransaction(Institution.HDFC, AccountType.CREDIT_CARD, dir,
                    amount(m.group(2)), currency(m.group(1)), m.group(4),
                    date(m.group(6)), time(m.group(7)), clean(m.group(5)), null, null, payment, null));
        }
        m = LEGACY.matcher(text);
        if (m.find()) {
            return Optional.of(new ParsedTransaction(Institution.HDFC, AccountType.CREDIT_CARD, Direction.DEBIT,
                    amount(m.group(3)), currency(m.group(2)), m.group(1),
                    date(m.group(5)), time(m.group(6)), clean(m.group(4)), null, null, false, null));
        }
        m = PAYMENT.matcher(text);
        if (m.find() && email.haystack().matches("(?s).*(received|credited).*")) {
            return Optional.of(new ParsedTransaction(Institution.HDFC, AccountType.CREDIT_CARD, Direction.CREDIT,
                    amount(m.group(2)), currency(m.group(1)), m.group(3),
                    null, null, "Card payment", null, null, true, null));
        }
        return Optional.empty();
    }
}
