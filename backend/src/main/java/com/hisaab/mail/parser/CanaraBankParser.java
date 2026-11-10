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
 * Canara Bank account alerts.
 * <p>Verified format: "An amount of INR 5,222.00 has been DEBITED on 03/11/26 from your account XXXX4471 to
 * Axis Bank with UPI Ref No.:600011112222. Total Available Balance INR 1250.40."
 * <p>Note: only a date is present, so the email's received time is used as the transaction time.
 */
@Component
public class CanaraBankParser implements BankEmailParser {

    private static final Pattern MAIN = Pattern.compile(
            "(?i)amount\\s+of\\s+(INR|Rs\\.?|₹)\\s*" + AMOUNT + "\\s+has\\s+been\\s+(DEBITED|CREDITED)"
                    + "\\s+on\\s+(\\d{2}/\\d{2}/\\d{2,4})\\s+(?:from|to|in|into)\\s+your\\s+(?:a/c|account)\\s+(?:no\\.?\\s*)?X*(\\d{4})");

    private static final Pattern PARTY = Pattern.compile(
            "^\\s*(?:to|from|by)\\s+(.+?)(?=\\s+with\\s|\\s+via\\s|\\s+towards\\s|\\.\\s|\\.?$)", Pattern.CASE_INSENSITIVE);

    private static final Pattern REF = Pattern.compile(
            "Ref(?:erence)?\\.?\\s*No\\.?\\s*:?\\s*([A-Za-z0-9]{6,})", Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "canara-bank";
    }

    @Override
    public Optional<ParsedTransaction> parse(EmailContent email) {
        if (!email.haystack().contains("canara")) return Optional.empty();
        String text = email.flat();
        Matcher m = MAIN.matcher(text);
        if (!m.find()) return Optional.empty();

        Direction dir = m.group(3).equalsIgnoreCase("DEBITED") ? Direction.DEBIT : Direction.CREDIT;
        String rest = text.substring(m.end());

        String party = null;
        Matcher pm = PARTY.matcher(rest);
        if (pm.find()) party = clean(pm.group(1));

        String ref = null;
        Matcher rm = REF.matcher(rest);
        if (rm.find()) ref = rm.group(1);

        BigDecimal balance = null;
        Matcher bm = BALANCE.matcher(rest);
        if (bm.find()) balance = amount(bm.group(1));

        return Optional.of(new ParsedTransaction(Institution.CANARA, AccountType.BANK, dir,
                amount(m.group(2)), currency(m.group(1)), m.group(5),
                date(m.group(4)), null, party, ref, balance, false, null));
    }
}
