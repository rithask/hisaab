package com.hisaab.mail.parser;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.Institution;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hisaab.mail.parser.ParseUtil.*;

/**
 * Heuristic parser for bank transaction SMS. Indian bank SMS vary a lot by bank and change often, so instead of
 * one regex per format it extracts each field independently: bank, direction, amount, account/card last 4,
 * date/time, counterparty, reference and balance. Examples it understands:
 * <ul>
 *   <li>HDFC UPI: "Sent Rs.500.00 From HDFC Bank A/C *2286 To RAHUL K On 05/11/26 Ref 531234567890 Not You?..."</li>
 *   <li>HDFC card: "Spent Rs.692 On HDFC Bank Card 6619 At SWIGGY INSTAMART On 2026-11-06:23:05:44 Not You?..."</li>
 *   <li>Axis: "INR 500.00 debited A/c no. XX2286 05-11-26, 10:00:00 UPI/P2A/531234567890/RAHUL K Not you?..."</li>
 *   <li>Axis card: "Spent INR 450 Axis Bank Card no. XX1234 05-11-26 10:00:00 IST ZOMATO Avl Limit: INR 9,550"</li>
 *   <li>SBI: "Dear UPI user A/C X5678 debited by 200.0 on date 05Nov26 trf to RAHUL K Refno 531234567890..."</li>
 *   <li>SBI Card: "Rs.1,299.00 spent on your SBI Credit Card ending 4321 at AMAZON on 05/11/26."</li>
 *   <li>Canara: "An amount of INR 500.00 has been CREDITED to your account XXX4471 on 05/11/2026 ..."</li>
 * </ul>
 */
@Component
public class SmsParser {

    /** Messages that mention money but aren't a completed transaction. */
    private static final Pattern NOT_A_TXN = Pattern.compile("(?i)\\b(otp|one[- ]time password|verification code"
            + "|will be (?:debited|charged|auto[- ]?debited)|is due|due (?:date|on|by)|min(?:imum)?\\.? amt due"
            + "|declined|failed|unsuccessful|has requested|requested (?:money|rs|inr)|collect request"
            + "|pre-?approved|loan offer|apply now|cashback of|win |reward points? (?:worth|of))");

    private static final String CUR = "(Rs\\.?|INR|₹|USD|EUR|GBP|AED|SGD|AUD|CAD|CHF|JPY|THB|MYR|SAR|QAR|HKD|LKR|NPR|CNY)";

    /** A currency-prefixed amount, with what came just before it so balances/limits can be skipped. */
    private static final Pattern MONEY = Pattern.compile("(?i)" + CUR + "\\s*" + AMOUNT);
    /** SBI style "debited by 200.0" with no currency. */
    private static final Pattern BARE_AMOUNT = Pattern.compile("(?i)\\b(?:debited|credited)\\s+(?:by|for|with)\\s+" + AMOUNT);
    private static final Pattern NOT_TXN_AMOUNT_CONTEXT = Pattern.compile(
            "(?i)(bal(?:ance)?|lmt|limit|avl|avail(?:able)?|outstanding|due|min)[\\s.:]*(?:is\\s*)?[:\\-]?\\s*$");

    private static final Pattern DEBIT_WORDS = Pattern.compile(
            "(?i)\\b(debited|spent|sent|withdrawn|withdrawal|paid|purchase|deducted|transferred|dr\\.?)\\b");
    private static final Pattern CREDIT_WORDS = Pattern.compile(
            "(?i)\\b(credited|received|deposited|refund(?:ed)?|reversed|reversal|cashback|cr\\.?)\\b");

    private static final Pattern LAST4 = Pattern.compile(
            "(?i)(?:a/?c|acct|account|card)\\b(?:\\s*(?:no\\.?|number|ending(?:\\s+with)?|ending\\s+in))?\\s*[:.-]?\\s*[x*•.]*\\s*(\\d{4})\\b");

    private static final Pattern CARD = Pattern.compile("(?i)(credit\\s*card|\\bcard\\s*(?:no|ending|x|\\*|\\d)|\\bcc\\b)");
    private static final Pattern DEBIT_CARD = Pattern.compile("(?i)debit\\s*card");

    private static final Pattern REF = Pattern.compile(
            "(?i)\\b(?:upi\\s*ref(?:\\.|erence)?(?:\\s*no\\.?)?|ref(?:erence)?(?:\\s*(?:no|number|id))?\\.?|refno|rrn|utr(?:\\s*no)?|txn\\s*id)\\s*[:#.-]?\\s*([A-Za-z0-9]*\\d{6,}[A-Za-z0-9]*)");

    private static final Pattern BALANCE_RX = Pattern.compile(
            "(?i)(?:avl\\.?|available|avail\\.?|a/c|total\\s+avail\\.?|clear)\\s*bal(?:ance)?\\.?\\s*(?:is|:|-)?\\s*(?:Rs\\.?|INR|₹)\\s*" + AMOUNT);

    private static final Pattern NARRATION = Pattern.compile(
            "(?i)\\b((?:UPI|IMPS|NEFT|RTGS|ACH|NACH|MMT)/[^\\s/]+(?:/[^/\\n]+?){1,5})(?=\\s+(?:not you|if not|avl|bal|call|sms|-\\s|ref)|[.;]\\s|$)");

    /** "to X", "at X", "towards X", "trf to X", "from X", "by X", "VPA x@y" followed by a stop word. */
    private static final String STOP = "(?=\\s+(?:on|via|ref|refno|upi|imps|neft|avl|avl\\.|not\\s+you|if\\s+not|using|thru|through|with|for|dated|date|bal|at\\s+\\d|is|has|-)\\b|\\s+-|[.;,(]|\\s*$)";
    private static final Pattern PAYEE = Pattern.compile("(?i)\\b(?:trf\\s+to|paid\\s+to|transferred\\s+to|to\\s+vpa|to|at|towards|info:?)\\s+(.+?)" + STOP);
    private static final Pattern PAYER = Pattern.compile("(?i)\\b(?:from\\s+vpa|from|by|transfer\\s+from)\\s+(.+?)" + STOP);
    private static final Pattern VPA = Pattern.compile("(?i)\\b([a-z0-9._-]{2,}@[a-z]{2,})\\b");
    private static final Pattern OWN_ACCOUNT_WORDS = Pattern.compile(
            "(?i)^(your|ur|a/?c|acct|account|card|hdfc|axis|sbi|canara|bank|upi|imps|neft|you)\\b|\\b(a/c|account|card)\\b|^[x*\\d\\s]+$");

    // Dates: 05-11-26, 05/11/2026, 05-Nov-26, 05 Nov 2026, 05Nov26, 2026-11-06, with optional time
    private static final Pattern DATE_TIME = Pattern.compile(
            "(?i)\\b(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ]?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[-, ]{0,2}\\d{2,4})"
                    + "(?:[\\s,:@]*(?:at\\s+)?(\\d{1,2}:\\d{2}(?::\\d{2})?))?");

    private static final List<java.time.format.DateTimeFormatter> EXTRA_DATES = List.of(
            f("ddMMMuu"), f("ddMMMuuuu"), f("d-M-uu"), f("d/M/uu"), f("d-M-uuuu"), f("d/M/uuuu"), f("dd MMM uu"),
            f("dd-MMMM-uu"), f("dd MMMM uuuu"), f("dd MMMM, uuuu"));

    public record Result(ParsedTransaction txn, String reason) {
        static Result skip(String reason) {
            return new Result(null, reason);
        }
    }

    /**
     * @param sender     SMS sender id if known (e.g. "VM-HDFCBK"), used to recognise the bank
     * @param receivedAt when the phone received the SMS; used when the text has no (plausible) date
     */
    public Result parse(String sender, String text, Instant receivedAt) {
        if (text == null || text.isBlank()) return Result.skip("Empty message");
        String flat = text.replaceAll("\\s+", " ").trim();
        if (NOT_A_TXN.matcher(flat).find()) return Result.skip("Not a completed transaction (OTP, reminder, offer or failed payment)");

        // ---- amount (+ currency) -------------------------------------------------------------------------
        BigDecimal amount = null;
        String currency = "INR";
        int amountAt = -1;
        Matcher mm = MONEY.matcher(flat);
        while (mm.find()) {
            String before = flat.substring(Math.max(0, mm.start() - 20), mm.start());
            if (NOT_TXN_AMOUNT_CONTEXT.matcher(before).find()) continue;
            amount = amount(mm.group(2));
            currency = currency(mm.group(1));
            amountAt = mm.start();
            break;
        }
        if (amount == null) {
            Matcher bm = BARE_AMOUNT.matcher(flat);
            if (bm.find()) {
                amount = amount(bm.group(1));
                amountAt = bm.start();
            }
        }
        if (amount == null || amount.signum() <= 0) return Result.skip("No transaction amount found");

        // ---- direction: the debit/credit word nearest the amount wins ----------------------------------
        Direction direction = nearest(flat, amountAt);
        if (direction == null) return Result.skip("Couldn't tell whether money went out or came in");

        // ---- bank, account type, last 4 -----------------------------------------------------------------
        Institution institution = institution(sender, flat);
        boolean card = CARD.matcher(flat).find() && !DEBIT_CARD.matcher(flat).find();
        String last4 = null;
        Matcher lm = LAST4.matcher(flat);
        while (lm.find()) {
            String ctx = flat.substring(Math.max(0, lm.start() - 12), lm.start()).toLowerCase(Locale.ROOT);
            if (ctx.contains("call") || ctx.contains("sms")) continue; // "call 1800..." / "SMS BLOCK 1234"
            last4 = lm.group(1);
            break;
        }

        // ---- when ---------------------------------------------------------------------------------------
        When w = findWhen(flat, receivedAt);
        LocalDate date = w.date();
        LocalTime time = w.time();

        // ---- who ----------------------------------------------------------------------------------------
        String reference = null;
        Matcher rm = REF.matcher(flat);
        if (rm.find()) reference = rm.group(1);

        String counterparty = null;
        String description = null;
        Matcher nm = NARRATION.matcher(flat);
        if (nm.find()) {
            description = clean(nm.group(1));
            RefAndParty rp = splitNarration(description);
            counterparty = rp.counterparty();
            if (reference == null) reference = rp.reference();
        }
        if (counterparty == null) {
            Matcher vm = VPA.matcher(flat);
            while (vm.find()) {
                boolean email = flat.length() > vm.end() + 1 && flat.charAt(vm.end()) == '.' && Character.isLetter(flat.charAt(vm.end() + 1));
                if (email || vm.group(1).matches("(?i).*(support|care|customer|help|service|grievance).*")) continue;
                counterparty = vm.group(1);
                break;
            }
        }
        if (counterparty == null) counterparty = party(flat, direction == Direction.DEBIT ? PAYEE : PAYER);
        // Confusing wording ("credited ... to SHOP"): better the other side's name than none.
        if (counterparty == null) counterparty = party(flat, direction == Direction.DEBIT ? PAYER : PAYEE);
        if (counterparty == null && card) counterparty = trailingMerchant(flat);

        BigDecimal balance = null;
        if (!card) {
            Matcher balm = BALANCE_RX.matcher(flat);
            if (balm.find()) balance = amount(balm.group(1));
        }

        boolean cardPayment = card && direction == Direction.CREDIT
                && flat.toLowerCase(Locale.ROOT).matches(".*\\b(payment|paid)\\b.*");
        if (cardPayment && counterparty == null) counterparty = "Card payment";

        return new Result(new ParsedTransaction(institution, card ? AccountType.CREDIT_CARD : AccountType.BANK, direction,
                amount, currency, last4, date, time, counterparty, reference, balance, cardPayment, description), null);
    }

    public record When(LocalDate date, LocalTime time) {}

    /** First plausible date (and time) in the text; implausible ones such as a card expiry are skipped. */
    public static When findWhen(String flat, Instant receivedAt) {
        Matcher dm = DATE_TIME.matcher(flat);
        while (dm.find()) {
            LocalDate d = parseDate(dm.group(1));
            if (d == null) continue;
            LocalDate ref = receivedAt == null ? LocalDate.now(com.hisaab.service.Money.INDIA)
                    : receivedAt.atZone(com.hisaab.service.Money.INDIA).toLocalDate();
            long days = ChronoUnit.DAYS.between(d, ref);
            if (days < -1 || days > 45) continue;
            LocalTime time = null;
            if (dm.group(2) != null) {
                String t = dm.group(2);
                time = LocalTime.parse(t.length() <= 5 ? (t.length() == 4 ? "0" + t : t) + ":00" : (t.length() == 7 ? "0" + t : t));
            }
            return new When(d, time);
        }
        return new When(null, null);
    }

    /** Span of the reference number in the text, or null. */
    public static int[] findReference(String flat) {
        Matcher m = REF.matcher(flat);
        return m.find() ? new int[]{m.start(1), m.end(1)} : null;
    }

    /** Span of the available-balance figure in the text, or null. */
    public static int[] findBalance(String flat) {
        Matcher m = BALANCE_RX.matcher(flat);
        return m.find() ? new int[]{m.start(1), m.end(1)} : null;
    }

    /** Span of the account/card last-4 digits in the text, or null. */
    public static int[] findLast4(String flat) {
        Matcher lm = LAST4.matcher(flat);
        while (lm.find()) {
            String ctx = flat.substring(Math.max(0, lm.start() - 12), lm.start()).toLowerCase(Locale.ROOT);
            if (ctx.contains("call") || ctx.contains("sms")) continue;
            return new int[]{lm.start(1), lm.end(1)};
        }
        return null;
    }

    public static BigDecimal toAmount(String s) {
        return amount(s);
    }

    private static Direction nearest(String flat, int amountAt) {
        int d = nearestIndex(DEBIT_WORDS, flat, amountAt);
        int c = nearestIndex(CREDIT_WORDS, flat, amountAt);
        if (d < 0 && c < 0) return null;
        if (d < 0) return Direction.CREDIT;
        if (c < 0) return Direction.DEBIT;
        return Math.abs(d - amountAt) <= Math.abs(c - amountAt) ? Direction.DEBIT : Direction.CREDIT;
    }

    private static int nearestIndex(Pattern p, String s, int at) {
        Matcher m = p.matcher(s);
        int best = -1;
        while (m.find()) {
            // "credited to your card" in a payment SMS is the transaction itself; skip "cr" inside "Card"
            if (best < 0 || Math.abs(m.start() - at) < Math.abs(best - at)) best = m.start();
        }
        return best;
    }

    public static Institution institution(String sender, String flat) {
        String s = (sender == null ? "" : sender).toUpperCase(Locale.ROOT);
        if (s.contains("HDFC")) return Institution.HDFC;
        if (s.contains("AXIS")) return Institution.AXIS;
        if (s.contains("SBI") || s.contains("CBSSBI")) return Institution.SBI;
        if (s.contains("CANBNK") || s.contains("CANARA")) return Institution.CANARA;
        String t = flat.toLowerCase(Locale.ROOT);
        if (t.contains("hdfc")) return Institution.HDFC;
        if (t.contains("axis")) return Institution.AXIS;
        if (t.contains("canara")) return Institution.CANARA;
        if (t.matches(".*\\b(sbi|sbi card|state bank)\\b.*") || t.endsWith("-sbi")) return Institution.SBI;
        return Institution.OTHER;
    }

    private static String party(String flat, Pattern p) {
        Matcher m = p.matcher(flat);
        while (m.find()) {
            String v = clean(m.group(1));
            if (v == null || v.length() < 2 || v.length() > 60) continue;
            if (OWN_ACCOUNT_WORDS.matcher(v).find()) continue;
            if (v.matches("(?i)\\d.*|rs\\.?.*|inr.*")) continue;
            if (v.matches("(?i)(us|you)\\b.*|.*(customer\\s*care|support|helpline|grievance|@[a-z]+bank).*")) continue;
            return v;
        }
        return null;
    }

    /** Axis card SMS put the merchant after the date/time: "... 05-11-26 10:00:00 IST ZOMATO Avl Limit ..." */
    private static String trailingMerchant(String flat) {
        Matcher m = Pattern.compile("(?i)\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:IST)?\\s+([A-Z][A-Z0-9 &*'._-]{1,40}?)(?=\\s+(?:avl|available|not you|if not|bal)\\b|[.;]|$)").matcher(flat);
        return m.find() ? clean(m.group(1)) : null;
    }

    private static LocalDate parseDate(String raw) {
        String v = raw.trim().replaceAll("\\s+", " ");
        try {
            return date(v);
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        String compact = v.replace(",", "");
        for (var fmt : EXTRA_DATES) {
            try {
                return LocalDate.parse(compact, fmt);
            } catch (java.time.format.DateTimeParseException ignored) {
                // next
            }
        }
        return null;
    }

    private static java.time.format.DateTimeFormatter f(String p) {
        return new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH);
    }
}
