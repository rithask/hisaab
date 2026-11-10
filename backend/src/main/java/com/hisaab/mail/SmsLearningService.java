package com.hisaab.mail;

import com.hisaab.domain.Account;
import com.hisaab.domain.AccountHint;
import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.Institution;
import com.hisaab.domain.SmsSender;
import com.hisaab.domain.SmsTemplate;
import com.hisaab.mail.parser.ParsedTransaction;
import com.hisaab.mail.parser.SmsParser;
import com.hisaab.repo.AccountHintRepo;
import com.hisaab.repo.SmsSenderRepo;
import com.hisaab.repo.SmsTemplateRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The SMS parser's memory. It learns three things, all from evidence rather than guesswork:
 * <ul>
 *   <li><b>Formats</b>: when I correct a message, its text becomes a template with the variable parts (amount,
 *       payee, account digits, reference, balance, dates) turned into slots. The next SMS with the same wording
 *       is read exactly, without heuristics.</li>
 *   <li><b>Accounts</b>: which of my accounts a bank's SMS means when it carries no number, or a different one.</li>
 *   <li><b>Senders</b>: which bank a sender id belongs to, for texts that don't name their bank.</li>
 * </ul>
 */
@Service
public class SmsLearningService {
    private static final Logger log = LoggerFactory.getLogger(SmsLearningService.class);

    private static final String NUM = "[0-9][0-9,]*(?:\\.[0-9]{1,2})?";
    private static final Pattern NUMBER = Pattern.compile(NUM);
    private static final Pattern MONTH = Pattern.compile(
            "(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*[.,]?|(mon|tue|wed|thu|fri|sat|sun)[a-z]*[.,]?|am|pm");

    private final SmsTemplateRepo templates;
    private final AccountHintRepo hints;
    private final SmsSenderRepo senders;

    public SmsLearningService(SmsTemplateRepo templates, AccountHintRepo hints, SmsSenderRepo senders) {
        this.templates = templates;
        this.hints = hints;
        this.senders = senders;
    }

    // ---- formats --------------------------------------------------------------------------------------------

    public record Match(ParsedTransaction txn, SmsTemplate template) {}

    /** Reads the SMS with a learned format, if one fits its wording exactly. */
    @Transactional
    public Optional<Match> match(String text, Instant receivedAt) {
        String flat = flat(text);
        for (SmsTemplate t : templates.findAllByOrderByHitsDescIdAsc()) {
            Matcher m;
            try {
                m = Pattern.compile(t.getPattern(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(flat);
            } catch (PatternSyntaxException e) {
                log.warn("Learned SMS format {} is unusable: {}", t.getId(), e.getMessage());
                continue;
            }
            if (!m.matches()) continue;
            BigDecimal amount = SmsParser.toAmount(m.group("amt"));
            if (amount.signum() <= 0) continue;
            SmsParser.When w = SmsParser.findWhen(flat, receivedAt);
            ParsedTransaction p = new ParsedTransaction(t.getInstitution(), t.getAccountType(), t.getDirection(), amount, "INR",
                    group(t, m, "l4"), w.date(), w.time(), clean(group(t, m, "payee")), group(t, m, "ref"),
                    t.getAccountType() == AccountType.CREDIT_CARD || group(t, m, "bal") == null ? null : SmsParser.toAmount(group(t, m, "bal")),
                    t.isCardPayment(), null);
            t.setHits(t.getHits() + 1);
            t.setLastUsedAt(Instant.now());
            return Optional.of(new Match(p, t));
        }
        return Optional.empty();
    }

    private static String group(SmsTemplate t, Matcher m, String name) {
        return t.getPattern().contains("(?<" + name + ">") ? m.group(name) : null;
    }

    /** What I said a message really was. */
    public record Truth(Direction direction, BigDecimal amount, Account account, String counterparty) {}

    /**
     * Turns a corrected SMS into a reusable format. Returns empty when the amount I gave doesn't appear in the
     * text, in which case there is nothing reliable to anchor a format on.
     */
    @Transactional
    public Optional<SmsTemplate> learn(String text, Truth truth) {
        String flat = flat(text);
        List<int[]> spans = new ArrayList<>(); // {start, end, kind}
        int[] amt = findAmount(flat, truth.amount());
        if (amt == null) return Optional.empty();
        spans.add(new int[]{amt[0], amt[1], 0});

        int[] l4 = SmsParser.findLast4(flat);
        if (l4 != null) add(spans, l4, 2);
        int[] ref = SmsParser.findReference(flat);
        if (ref != null) add(spans, ref, 3);
        int[] bal = SmsParser.findBalance(flat);
        if (bal != null) add(spans, bal, 4);
        if (truth.counterparty() != null && !truth.counterparty().isBlank()) {
            int at = flat.toLowerCase(Locale.ROOT).indexOf(truth.counterparty().trim().toLowerCase(Locale.ROOT));
            if (at >= 0) add(spans, new int[]{at, at + truth.counterparty().trim().length()}, 1);
        }
        spans.sort(Comparator.comparingInt(s -> s[0]));

        StringBuilder pattern = new StringBuilder("^");
        StringBuilder skeleton = new StringBuilder();
        int pos = 0;
        for (int i = 0; i <= spans.size(); i++) {
            int end = i < spans.size() ? spans.get(i)[0] : flat.length();
            literal(flat.substring(pos, end), pattern, skeleton);
            if (i == spans.size()) break;
            int[] s = spans.get(i);
            boolean last = s[1] >= flat.length();
            switch (s[2]) {
                case 0 -> slot(pattern, skeleton, "(?<amt>" + NUM + ")", "<AMT>");
                case 1 -> slot(pattern, skeleton, last ? "(?<payee>.+)" : "(?<payee>.+?)", "<PAYEE>");
                case 2 -> slot(pattern, skeleton, "(?<l4>\\d{4})", "<L4>");
                case 3 -> slot(pattern, skeleton, "(?<ref>[A-Za-z0-9]+)", "<REF>");
                default -> slot(pattern, skeleton, "(?<bal>" + NUM + ")", "<BAL>");
            }
            pos = s[1];
        }
        pattern.append("$");

        String key = skeleton.toString().trim();
        Account a = truth.account();
        SmsTemplate t = templates.findBySkeleton(key).orElseGet(SmsTemplate::new);
        t.setSkeleton(key);
        t.setPattern(pattern.toString());
        t.setInstitution(a.getInstitution());
        t.setAccountType(a.isCard() ? AccountType.CREDIT_CARD : AccountType.BANK);
        t.setDirection(truth.direction());
        t.setCardPayment(a.isCard() && truth.direction() == Direction.CREDIT);
        t.setSample(text.strip());
        // Sanity check: a format that can't read back its own source would only cause confusion.
        Matcher self = Pattern.compile(t.getPattern(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(flat);
        if (!self.matches() || SmsParser.toAmount(self.group("amt")).compareTo(truth.amount()) != 0) return Optional.empty();
        return Optional.of(templates.save(t));
    }

    private static void add(List<int[]> spans, int[] span, int kind) {
        for (int[] s : spans) if (span[0] < s[1] && s[0] < span[1]) return; // overlaps something already claimed
        spans.add(new int[]{span[0], span[1], kind});
    }

    private static void slot(StringBuilder pattern, StringBuilder skeleton, String rx, String label) {
        pattern.append(rx);
        skeleton.append(label);
    }

    /**
     * Fixed wording between slots. Words are matched literally; anything containing a digit (dates, times, phone
     * numbers) and month/day names become wildcards, because they change from message to message.
     */
    private static void literal(String chunk, StringBuilder pattern, StringBuilder skeleton) {
        Matcher m = Pattern.compile("\\s+|\\S+").matcher(chunk);
        while (m.find()) {
            String tok = m.group();
            if (tok.isBlank()) {
                pattern.append("\\s+");
                skeleton.append(' ');
            } else if (MONTH.matcher(tok).matches()) {
                pattern.append("\\S+");
                skeleton.append('#');
            } else if (tok.chars().anyMatch(Character::isDigit)) {
                // Keep the token's shape (UPI/P2M/<digits>/, dd-mm-yy, hh:mm:ss) but let the digits vary, so a
                // wildcard can never swallow part of a neighbouring slot.
                Matcher part = Pattern.compile("[0-9][0-9,.]*|[A-Za-z]+|[^0-9A-Za-z]+").matcher(tok);
                while (part.find()) {
                    String g = part.group();
                    if (Character.isDigit(g.charAt(0))) pattern.append("[0-9][0-9,.]*");
                    else if (MONTH.matcher(g).matches()) pattern.append("[A-Za-z]+");
                    else pattern.append(Pattern.quote(g));
                }
                skeleton.append('#');
            } else {
                pattern.append(Pattern.quote(tok));
                skeleton.append(tok.toLowerCase(Locale.ROOT));
            }
        }
    }

    /** Span of the number in the text equal to {@code amount}; prefers one that isn't labelled as a balance. */
    static int[] findAmount(String flat, BigDecimal amount) {
        int[] bal = SmsParser.findBalance(flat);
        int[] fallback = null;
        Matcher m = NUMBER.matcher(flat);
        while (m.find()) {
            // must be a standalone number, not digits inside an account or reference
            boolean glued = (m.start() > 0 && Character.isLetterOrDigit(flat.charAt(m.start() - 1)) && Character.isDigit(flat.charAt(m.start() - 1)))
                    || (m.end() < flat.length() && Character.isDigit(flat.charAt(m.end())));
            if (glued) continue;
            BigDecimal v;
            try {
                v = SmsParser.toAmount(m.group());
            } catch (RuntimeException e) {
                continue;
            }
            if (v.compareTo(amount) != 0) continue;
            int[] span = {m.start(), m.end()};
            if (bal != null && bal[0] == span[0]) {
                fallback = span;
                continue;
            }
            return span;
        }
        return fallback;
    }

    public List<SmsTemplate> templates() {
        return templates.findAllByOrderByHitsDescIdAsc();
    }

    @Transactional
    public void forgetTemplate(Long id) {
        templates.deleteById(id);
    }

    // ---- accounts -------------------------------------------------------------------------------------------

    public Optional<Account> hintedAccount(Institution institution, String last4) {
        return hints.findByInstitutionAndLast4(institution, last4 == null ? "" : last4)
                .map(AccountHint::getAccount).filter(a -> !a.isArchived());
    }

    /** Remembers that this bank's SMS with these digits (or none) means this account. */
    @Transactional
    public void rememberAccount(Institution institution, String last4, Account account) {
        String key = last4 == null ? "" : last4;
        AccountHint h = hints.findByInstitutionAndLast4(institution, key).orElseGet(AccountHint::new);
        h.setInstitution(institution);
        h.setLast4(key);
        h.setAccount(account);
        hints.save(h);
    }

    // ---- senders --------------------------------------------------------------------------------------------

    /** "VM-HDFCBK-S" -> "HDFCBK": the part of a sender id that stays the same across operators and routes. */
    static String senderKey(String sender) {
        if (sender == null) return null;
        String s = sender.trim().toUpperCase(Locale.ROOT).replaceAll("^[A-Z]{2}-", "").replaceAll("-[A-Z]$", "").replaceAll("[^A-Z0-9]", "");
        return s.length() < 3 || s.length() > 40 || s.matches("\\d+") ? null : s;
    }

    @Transactional
    public void rememberSender(String sender, Institution institution) {
        String key = senderKey(sender);
        if (key == null || institution == Institution.OTHER) return;
        SmsSender s = senders.findById(key).orElseGet(() -> new SmsSender(key, institution));
        s.setInstitution(institution);
        s.setHits(s.getHits() + 1);
        senders.save(s);
    }

    public Optional<Institution> senderInstitution(String sender) {
        String key = senderKey(sender);
        return key == null ? Optional.empty() : senders.findById(key).map(SmsSender::getInstitution);
    }

    public long senderCount() {
        return senders.count();
    }

    private static String flat(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String clean(String s) {
        if (s == null) return null;
        String v = s.replaceAll("\\s+", " ").trim().replaceAll("[.,;:]+$", "").trim();
        return v.isEmpty() ? null : v;
    }
}
