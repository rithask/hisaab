package com.hisaab.mail.parser;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.Institution;
import com.hisaab.mail.EmailContent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hisaab.mail.parser.ParseUtil.*;

/**
 * Axis Bank savings/current account alerts.
 * <p>Verified format (an HTML table): "Amount Debited: INR 6000.00 | Account Number: XX2286 |
 * Date &amp; Time: 03-11-26, 08:54:07 IST | Transaction Info: UPI/P2A/612345678901/ARJUN RAVI K".
 * <p>The older one-line "INR 500.00 was debited from your A/c no. XX1234 on 01-01-24 ..." format is
 * handled on a best-effort basis.
 */
@Component
public class AxisBankAccountParser implements BankEmailParser {

    private static final Pattern AMOUNT_ROW = Pattern.compile(
            "(?i)Amount\\s+(Debited|Credited)\\s*:?\\s*(INR|Rs\\.?|₹)\\s*" + AMOUNT);
    private static final Pattern ACCOUNT_ROW = Pattern.compile(
            "(?i)Account\\s+(?:Number|No\\.?)\\s*:?\\s*X*(\\d{4})");
    private static final Pattern DATE_ROW = Pattern.compile(
            "(?i)Date\\s*&\\s*Time\\s*:?\\s*(\\d{2}-\\d{2}-\\d{2,4})\\s*,?\\s*(\\d{2}:\\d{2}:\\d{2})?");
    private static final Pattern INFO_ROW = Pattern.compile(
            "(?i)Transaction\\s+Info\\s*:?\\s*\\n?\\s*([^\\n]+)");

    private static final Pattern LEGACY = Pattern.compile(
            "(?i)(INR|Rs\\.?|₹)\\s*" + AMOUNT + "\\s+(?:has\\s+been|was|is)\\s+(debited|credited)\\s+(?:from|to)\\s+your\\s+"
                    + "A/c\\s+(?:no\\.?\\s*)?X*(\\d{4})\\s+on\\s+(\\d{2}-\\d{2}-\\d{2,4})(?:,?\\s+(?:at\\s+)?(\\d{2}:\\d{2}:\\d{2}))?");
    private static final Pattern LEGACY_INFO = Pattern.compile("(?i)Info\\s*[-:]\\s*(\\S+(?: \\S+){0,4})");

    @Override
    public String name() {
        return "axis-bank-account";
    }

    @Override
    public Optional<ParsedTransaction> parse(EmailContent email) {
        String hay = email.haystack();
        if (!hay.contains("axis")) return Optional.empty();
        // Axis credit card alerts are a different product; don't claim them here.
        if (hay.matches("(?s).*credit card (no|number|ending|xx).*")) return Optional.empty();

        String flat = email.flat();
        BigDecimal balance = null;
        Matcher bm = BALANCE.matcher(flat);
        if (bm.find()) balance = amount(bm.group(1));

        Matcher am = AMOUNT_ROW.matcher(flat);
        Matcher acm = ACCOUNT_ROW.matcher(flat);
        Matcher dm = DATE_ROW.matcher(flat);
        if (am.find() && acm.find() && dm.find()) {
            Direction dir = am.group(1).equalsIgnoreCase("Debited") ? Direction.DEBIT : Direction.CREDIT;
            String info = null;
            Matcher im = INFO_ROW.matcher(email.text());
            if (im.find()) info = im.group(1).trim();
            RefAndParty rp = splitNarration(info);
            return Optional.of(new ParsedTransaction(Institution.AXIS, AccountType.BANK, dir,
                    amount(am.group(3)), currency(am.group(2)), acm.group(1),
                    date(dm.group(1)), time(dm.group(2)), rp.counterparty(), rp.reference(), balance, false, info));
        }

        Matcher lm = LEGACY.matcher(flat);
        if (lm.find()) {
            Direction dir = lm.group(3).equalsIgnoreCase("debited") ? Direction.DEBIT : Direction.CREDIT;
            String info = null;
            Matcher im = LEGACY_INFO.matcher(flat.substring(lm.end()));
            if (im.find()) info = clean(im.group(1));
            RefAndParty rp = splitNarration(info);
            return Optional.of(new ParsedTransaction(Institution.AXIS, AccountType.BANK, dir,
                    amount(lm.group(2)), currency(lm.group(1)), lm.group(4),
                    date(lm.group(5)), time(lm.group(6)), rp.counterparty(), rp.reference(), balance, false, info));
        }
        return Optional.empty();
    }
}
