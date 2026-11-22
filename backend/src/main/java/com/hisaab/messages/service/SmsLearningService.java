package com.hisaab.messages.service;

import com.hisaab.common.model.AccountType;
import com.hisaab.common.model.Direction;
import com.hisaab.messages.model.Channel;
import com.hisaab.accounts.model.Account;
import com.hisaab.messages.model.LearnedExample;
import com.hisaab.messages.model.RawEmail;
import com.hisaab.messages.model.SmsTemplate;
import com.hisaab.messages.parser.SmsParser;
import com.hisaab.messages.repository.LearnedExampleRepository;
import com.hisaab.messages.repository.SmsTemplateRepository;
import com.hisaab.messages.util.FormatShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SMS parser's memory for formats, learned from evidence rather than guesswork: when I correct a message, its
 * text becomes a template with the variable parts (amount, payee, account digits, reference, balance, dates) turned
 * into slots, so the next message with the same wording is read exactly, without heuristics. Reading a message with a learned format is {@link SmsReader}; accounts and sender ids are {@link SmsSenders}.
 */
@Service
public class SmsLearningService {
    private static final Logger log = LoggerFactory.getLogger(SmsLearningService.class);

    private static final Pattern MONTH = Pattern.compile(
            "(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*[.,]?|(mon|tue|wed|thu|fri|sat|sun)[a-z]*[.,]?|am|pm");

    private final SmsTemplateRepository templates;
    private final LearnedExampleRepository examples;

    public SmsLearningService(SmsTemplateRepository templates, LearnedExampleRepository examples) {
        this.examples = examples;
        this.templates = templates;
    }

    // ---- formats --------------------------------------------------------------------------------------------

    /**
     * What I said a message really was. {@code amountAt} and {@code payeeAt} are where I marked the amount and the
     * payee in the message ({start, end} in its whitespace-collapsed text), when I marked them.
     */
    public record Truth(Direction direction, BigDecimal amount, Account account, String counterparty, int[] amountAt, int[] payeeAt) {
        public Truth(Direction direction, BigDecimal amount, Account account, String counterparty) {
            this(direction, amount, account, counterparty, null, null);
        }
    }

    /** A {start, end} span that lies inside the text, or null. */
    public static int[] span(int[] s, String flat) {
        if (s == null || s.length != 2 || s[0] < 0 || s[1] > flat.length() || s[0] >= s[1]) return null;
        // trim spaces and trailing punctuation so the slot lines up with the words
        int a = s[0], b = s[1];
        while (a < b && Character.isWhitespace(flat.charAt(a))) a++;
        while (b > a && (Character.isWhitespace(flat.charAt(b - 1)) || ".,;:".indexOf(flat.charAt(b - 1)) >= 0)) b--;
        return a < b ? new int[]{a, b} : null;
    }

    /** The number inside a marked span (the amount slot covers just the digits, not "Rs." around them). */
    static int[] numberIn(String flat, int[] s, BigDecimal amount) {
        if (s == null) return null;
        Matcher m = SmsReader.NUMBER.matcher(flat).region(s[0], s[1]);
        while (m.find()) {
            try {
                if (amount == null || SmsParser.toAmount(m.group()).compareTo(amount) == 0) return new int[]{m.start(), m.end()};
            } catch (RuntimeException ignored) {
                // not a number after all
            }
        }
        return null;
    }

    /**
     * @param template  the format learned, if any
     * @param conflicts when a format was refused by the safety net: how many earlier lessons it would have broken
     */
    public record LearnResult(Optional<SmsTemplate> template, int conflicts, boolean merged) {
        LearnResult(Optional<SmsTemplate> template, int conflicts) {
            this(template, conflicts, false);
        }

        static LearnResult none() {
            return new LearnResult(Optional.empty(), 0);
        }
    }

    /** Turns a corrected SMS into a reusable format (see {@link #teach}). */
    @Transactional
    public Optional<SmsTemplate> learn(String text, Truth truth) {
        return teach(Channel.SMS, text, truth, null).template();
    }

    @Transactional
    public LearnResult teach(String text, Truth truth, RawEmail source) {
        return teach(Channel.SMS, text, truth, source);
    }

    /**
     * Turns a corrected message into a reusable format, and keeps the message with its answer as a lesson.
     * Nothing is learned when the amount I gave doesn't appear in the text (nothing reliable to anchor on), or when
     * the new format would make any earlier lesson read differently from how I taught it.
     */
    @Transactional
    public LearnResult teach(Channel channel, String text, Truth truth, RawEmail source) {
        String flat = SmsReader.flat(text);
        Optional<SmsTemplate> built = build(flat, truth);
        if (built.isEmpty()) return LearnResult.none();
        SmsTemplate candidate = built.get();
        candidate.setSample(text.strip());
        candidate.setChannel(channel);

        // The safety net: the formats as they'd be after this change must read every other lesson as well as now.
        List<SmsTemplate> before = templates.findByChannelOrderByHitsDescIdAsc(channel);
        Optional<SmsTemplate> same = before.stream().filter(t -> t.getSkeleton().equals(candidate.getSkeleton())).findFirst();
        List<SmsTemplate> after = new ArrayList<>(before);
        same.ifPresent(after::remove);
        // I now say this wording is a transaction: "not a transaction" formats that cover it go
        List<SmsTemplate> overruled = before.stream().filter(SmsTemplate::isSkip)
                .filter(t -> SmsReader.read(List.of(t), flat).isPresent()).toList();
        after.removeAll(overruled);
        after.add(candidate);
        Check check = check(channel, before, after, flat, false);
        if (check.blocking() > 0) {
            log.info("{} format not learned: it would change how {} earlier lesson(s) are read", channel, check.blocking());
            return new LearnResult(Optional.empty(), check.blocking());
        }

        // A message of a kind already learned in slightly different wording: one format for both, if that's safe.
        if (same.isEmpty()) {
            Optional<LearnResult> merged = mergeInto(channel, before, overruled, candidate, flat, text, truth, source);
            if (merged.isPresent()) {
                merged.get().template().ifPresent(m -> log.info("{} format taught: merged into format {} ({})", channel, m.getId(), m.getInstitution()));
                return merged.get();
            }
        }

        overruled.forEach(templates::delete);
        check.apply(examples);
        SmsTemplate t = same.orElseGet(SmsTemplate::new);
        copy(candidate, t);
        // taught again in this exact wording: a fresh start, and it's mine now
        t.setCorrections(0);
        t.setPaused(false);
        t.setOrigin(SmsTemplate.TAUGHT);
        SmsTemplate saved = templates.save(t);
        remember(channel, flat, text, truth, source);
        log.info("{} format taught: {} format {} ({}){}", channel, same.isPresent() ? "replaced" : "new", saved.getId(), saved.getInstitution(),
                overruled.isEmpty() ? "" : ", " + overruled.size() + " \"not a transaction\" format(s) removed");
        return new LearnResult(Optional.of(saved), 0);
    }

    /**
     * "Not a transaction": messages worded like this one are skipped from now on. Digits and month names vary, the
     * rest of the wording must match. Refused when it would skip a message I taught as a transaction.
     */
    @Transactional
    public LearnResult skip(Channel channel, String text, RawEmail source) {
        String flat = SmsReader.flat(text);
        if (flat.isEmpty()) return LearnResult.none();
        FormatShape.Builder shape = new FormatShape.Builder();
        literal(flat, shape);
        FormatShape f = shape.done();
        SmsTemplate candidate = new SmsTemplate();
        candidate.setChannel(channel);
        candidate.setKind(SmsTemplate.SKIP);
        candidate.setSkeleton("skip: " + f.skeleton());
        candidate.setPattern(f.pattern());
        candidate.setParts(f.encode());
        candidate.setSample(text.strip());

        List<SmsTemplate> before = templates.findByChannelOrderByHitsDescIdAsc(channel);
        Optional<SmsTemplate> same = before.stream().filter(t -> t.getSkeleton().equals(candidate.getSkeleton())).findFirst();
        List<SmsTemplate> after = new ArrayList<>(before);
        same.ifPresent(after::remove);
        // a skip is checked before the formats that read transactions
        after.add(0, candidate);
        Check check = check(channel, before, after, flat, false);
        if (check.blocking() > 0) {
            log.info("{} \"not a transaction\" not learned: it would skip {} message(s) taught as transactions", channel, check.blocking());
            return new LearnResult(Optional.empty(), check.blocking());
        }
        check.apply(examples);

        SmsTemplate t = same.orElseGet(SmsTemplate::new);
        copy(candidate, t);
        t.setOrigin(SmsTemplate.TAUGHT);
        t.setKind(SmsTemplate.SKIP);
        t.setInstitution(null);
        t.setAccountType(null);
        t.setDirection(null);
        SmsTemplate saved = templates.save(t);
        for (LearnedExample old : examples.findByChannel(channel)) {
            if (SmsReader.flat(old.getText()).equals(flat)) examples.delete(old);
        }
        LearnedExample e = new LearnedExample();
        e.setRawEmail(source);
        e.setChannel(channel);
        e.setText(text.strip());
        e.setKind(LearnedExample.SKIP);
        examples.save(e);
        log.info("{} \"not a transaction\" learned: format {}", channel, saved.getId());
        return new LearnResult(Optional.of(saved), 0);
    }

    /**
     * Learns a format from a reading the app confirmed on its own (the balance in the message adds up, or the
     * email and SMS for the same payment agree), so the next message in that wording is read exactly. Marked as
     * learned automatically; it's removed the first time a reading by it is corrected. Nothing happens when a
     * learned format already reads the message, or when the safety net would object to any lesson.
     */
    @Transactional
    public LearnResult autoLearn(Channel channel, String text, Truth truth, RawEmail source) {
        String flat = SmsReader.flat(text);
        List<SmsTemplate> before = templates.findByChannelOrderByHitsDescIdAsc(channel);
        if (SmsReader.read(before, flat).isPresent()) return LearnResult.none();
        Optional<SmsTemplate> built = build(flat, truth);
        if (built.isEmpty()) return LearnResult.none();
        SmsTemplate candidate = built.get();
        candidate.setSample(text.strip());
        candidate.setChannel(channel);
        candidate.setOrigin(SmsTemplate.AUTO);
        if (before.stream().anyMatch(t -> t.getSkeleton().equals(candidate.getSkeleton()))) return LearnResult.none();
        List<SmsTemplate> after = new ArrayList<>(before);
        after.add(candidate);
        if (regressions(channel, before, after, flat, true) > 0) return LearnResult.none();
        SmsTemplate saved = templates.save(candidate);
        remember(channel, flat, text, truth, source);
        examples.findByChannel(channel).stream().filter(e -> SmsReader.flat(e.getText()).equals(flat))
                .forEach(e -> e.setOrigin(SmsTemplate.AUTO));
        log.info("{} format learned automatically: format {} ({})", channel, saved.getId(), saved.getInstitution());
        return new LearnResult(Optional.of(saved), 0);
    }

    /** Corrections after which a format stops being used. */
    static final int PAUSE_AFTER = 2;

    /**
     * A message this format read was corrected: count it if the format's reading differs from what I said, and
     * pause the format once it has been wrong {@value #PAUSE_AFTER} times. (Teaching the same wording again
     * replaces the format and clears the count, see {@link #teach}.)
     *
     * @param truth what the message really was, or null for "not a transaction"
     * @return true if the format was paused now
     */
    @Transactional
    public boolean corrected(SmsTemplate t, String text, Truth truth) {
        if (t == null) return false;
        Optional<SmsReader.Read> r = SmsReader.read(List.of(SmsReader.withoutPause(t)), SmsReader.flat(text));
        boolean wrong;
        if (truth == null) {
            wrong = r.isEmpty() || !r.get().skip();
        } else {
            wrong = !right(lessonOf(SmsReader.flat(text), truth), r);
        }
        if (!wrong) return false;
        if (t.isAuto()) {
            // learned automatically and now corrected: it goes
            log.info("Format {} ({}) learned automatically was corrected, so it's forgotten", t.getId(), t.getInstitution());
            templates.delete(t);
            return false;
        }
        t.setCorrections(t.getCorrections() + 1);
        if (t.getCorrections() >= PAUSE_AFTER && !t.isPaused()) {
            t.setPaused(true);
            log.warn("Format {} ({}) paused after {} corrections: its messages now wait for review", t.getId(), t.getInstitution(), t.getCorrections());
            return true;
        }
        return false;
    }

    @Transactional
    public void resume(Long id) {
        templates.findById(id).ifPresent(t -> {
            t.setPaused(false);
            t.setCorrections(0);
        });
    }

    /**
     * Tries to fold a new format into an existing one for the same kind of message (same bank, account type and
     * direction) whose wording mostly matches: the words that differ become "any words". Kept only if the merged
     * format reads this message as taught and every lesson as before (the safety net).
     */
    private Optional<LearnResult> mergeInto(Channel channel, List<SmsTemplate> before, List<SmsTemplate> overruled,
                                            SmsTemplate candidate, String flat, String text, Truth truth, RawEmail source) {
        Optional<FormatShape> mine = FormatShape.decode(candidate.getParts());
        if (mine.isEmpty()) return Optional.empty();
        LearnedExample lesson = lessonOf(flat, truth);
        for (SmsTemplate e : before) {
            if (e.isSkip() || e.isPaused() || e.getInstitution() != candidate.getInstitution()
                    || e.getAccountType() != candidate.getAccountType() || e.getDirection() != candidate.getDirection()
                    || e.isCardPayment() != candidate.isCardPayment()) continue;
            Optional<FormatShape> both = FormatShape.decode(e.getParts()).flatMap(theirs -> FormatShape.merge(theirs, mine.get()));
            if (both.isEmpty()) continue;
            SmsTemplate m = new SmsTemplate();
            copy(e, m);
            m.setId(e.getId());
            shape(m, both.get());
            if (before.stream().anyMatch(x -> x != e && x.getSkeleton().equals(m.getSkeleton()))) continue;
            if (!right(lesson, SmsReader.read(List.of(m), flat))) continue;
            List<SmsTemplate> after = new ArrayList<>(before);
            after.remove(e);
            after.removeAll(overruled);
            after.add(m);
            Check check = check(channel, before, after, flat, false);
            if (check.blocking() > 0) continue;

            overruled.forEach(templates::delete);
            check.apply(examples);
            e.setOrigin(SmsTemplate.TAUGHT);
            e.setSkeleton(m.getSkeleton());
            e.setPattern(m.getPattern());
            e.setParts(m.getParts());
            SmsTemplate saved = templates.save(e);
            remember(channel, flat, text, truth, source);
            return Optional.of(new LearnResult(Optional.of(saved), 0, true));
        }
        return Optional.empty();
    }

    /** The lesson a correction makes, for checking a reading against it. */
    private static LearnedExample lessonOf(String flat, Truth truth) {
        LearnedExample e = new LearnedExample();
        e.setDirection(truth.direction());
        e.setAmount(truth.amount());
        e.setInstitution(truth.account().getInstitution());
        int[] marked = span(truth.payeeAt(), flat);
        e.setPayee(marked != null ? SmsReader.clean(flat.substring(marked[0], marked[1])) : null);
        return e;
    }

    /** How many lessons (other than {@code exceptText}) are read right by {@code before} but not by {@code after}. */
    int regressions(Channel channel, List<SmsTemplate> before, List<SmsTemplate> after, String exceptText) {
        Check c = check(channel, before, after, exceptText, false);
        return c.blocking();
    }

    int regressions(Channel channel, List<SmsTemplate> before, List<SmsTemplate> after, String exceptText, boolean auto) {
        return check(channel, before, after, exceptText, auto).blocking();
    }

    /** @param outranked automatic lessons a change I teach would break: dropped if the change is made */
    record Check(int blocking, List<LearnedExample> outranked) {
        void apply(LearnedExampleRepository repo) {
            outranked.forEach(repo::delete);
        }
    }

    /**
     * @param auto the change is learned automatically: then every lesson counts. For a change I teach, only my own
     *             lessons can block it; automatic lessons it would break are outranked (I know better).
     */
    Check check(Channel channel, List<SmsTemplate> before, List<SmsTemplate> after, String exceptText, boolean auto) {
        int n = 0;
        List<LearnedExample> outranked = new ArrayList<>();
        // a paused format still counts for the lessons it reads: pausing mustn't let a change slip past them
        List<SmsTemplate> was = before.stream().map(SmsReader::withoutPause).toList();
        for (LearnedExample e : examples.findByChannel(channel)) {
            String f = SmsReader.flat(e.getText());
            if (f.equals(exceptText)) continue;
            Optional<SmsReader.Read> now = SmsReader.read(after, f);
            boolean broken = right(e, SmsReader.read(was, f)) && !right(e, now);
            // never skip a message I said was a transaction, even one no format reads yet
            boolean hidden = LearnedExample.TXN.equals(e.getKind()) && now.isPresent() && now.get().skip();
            // something learned automatically may never misread a lesson I taught, even one nothing read before
            boolean misread = auto && !SmsTemplate.AUTO.equals(e.getOrigin()) && now.isPresent() && !right(e, now);
            if (!(broken || hidden || misread)) continue;
            if (!auto && SmsTemplate.AUTO.equals(e.getOrigin())) {
                outranked.add(e);
                continue;
            }
            n++;
        }
        return new Check(n, outranked);
    }

    static boolean right(LearnedExample e, Optional<SmsReader.Read> r) {
        if (LearnedExample.SKIP.equals(e.getKind())) return r.isPresent() && r.get().skip();
        if (r.isEmpty() || r.get().skip()) return false;
        SmsReader.Read x = r.get();
        return x.direction() == e.getDirection() && x.amount().compareTo(e.getAmount()) == 0
                && x.institution() == e.getInstitution()
                && (e.getPayee() == null || e.getPayee().equalsIgnoreCase(x.payee()));
    }

    /** Keeps the message and its answer as a lesson, replacing any earlier lesson on the same text. */
    private void remember(Channel channel, String flat, String text, Truth truth, RawEmail source) {
        for (LearnedExample old : examples.findByChannel(channel)) {
            if (SmsReader.flat(old.getText()).equals(flat)) examples.delete(old);
        }
        LearnedExample e = new LearnedExample();
        e.setRawEmail(source);
        e.setChannel(channel);
        e.setText(text.strip());
        e.setDirection(truth.direction());
        e.setAmount(truth.amount().setScale(2, RoundingMode.HALF_UP));
        e.setInstitution(truth.account().getInstitution());
        e.setAccountType(truth.account().isCard() ? AccountType.CREDIT_CARD : AccountType.BANK);
        int[] marked = span(truth.payeeAt(), flat);
        String payee = marked != null ? SmsReader.clean(flat.substring(marked[0], marked[1]))
                : truth.counterparty() == null ? null : truth.counterparty().trim();
        e.setPayee(payee != null && flat.toLowerCase(Locale.ROOT).contains(payee.toLowerCase(Locale.ROOT)) ? payee : null);
        examples.save(e);
    }

    private static void copy(SmsTemplate from, SmsTemplate to) {
        to.setSkeleton(from.getSkeleton());
        to.setPattern(from.getPattern());
        to.setParts(from.getParts());
        to.setInstitution(from.getInstitution());
        to.setAccountType(from.getAccountType());
        to.setDirection(from.getDirection());
        to.setCardPayment(from.isCardPayment());
        to.setSample(from.getSample());
        to.setChannel(from.getChannel());
        to.setKind(from.getKind());
    }

    /** The format a corrected message implies, checked to read its own source back; not saved. */
    private Optional<SmsTemplate> build(String flat, Truth truth) {
        List<int[]> spans = new ArrayList<>(); // {start, end, kind}
        int[] amt = numberIn(flat, span(truth.amountAt(), flat), truth.amount());
        if (amt == null) amt = SmsReader.findAmount(flat, truth.amount());
        if (amt == null) return Optional.empty();
        spans.add(new int[]{amt[0], amt[1], 0});

        int[] l4 = SmsParser.findLast4(flat);
        if (l4 != null) add(spans, l4, 2);
        int[] ref = SmsParser.findReference(flat);
        if (ref != null) add(spans, ref, 3);
        int[] bal = SmsParser.findBalance(flat);
        if (bal != null) add(spans, bal, 4);
        int[] marked = span(truth.payeeAt(), flat);
        if (marked != null) {
            // what I marked wins over the account digits, reference or balance the heuristics thought they saw there
            spans.removeIf(s -> s[2] != 0 && marked[0] < s[1] && s[0] < marked[1]);
            add(spans, marked, 1);
        } else if (truth.counterparty() != null && !truth.counterparty().isBlank()) {
            int at = flat.toLowerCase(Locale.ROOT).indexOf(truth.counterparty().trim().toLowerCase(Locale.ROOT));
            if (at >= 0) add(spans, new int[]{at, at + truth.counterparty().trim().length()}, 1);
        }
        spans.sort(Comparator.comparingInt(s -> s[0]));

        FormatShape.Builder shape = new FormatShape.Builder();
        int pos = 0;
        for (int i = 0; i <= spans.size(); i++) {
            int end = i < spans.size() ? spans.get(i)[0] : flat.length();
            literal(flat.substring(pos, end), shape);
            if (i == spans.size()) break;
            int[] s = spans.get(i);
            switch (s[2]) {
                case 0 -> shape.piece("<AMT>", "(?<amt>" + SmsReader.NUM + ")");
                case 1 -> shape.piece("<PAYEE>", "(?<payee>.+?)");
                case 2 -> shape.piece("<L4>", "(?<l4>\\d{4})");
                case 3 -> shape.piece("<REF>", "(?<ref>[A-Za-z0-9]+)");
                default -> shape.piece("<BAL>", "(?<bal>" + SmsReader.NUM + ")");
            }
            pos = s[1];
        }
        FormatShape f = shape.done();

        Account a = truth.account();
        SmsTemplate t = new SmsTemplate();
        shape(t, f);
        t.setInstitution(a.getInstitution());
        t.setAccountType(a.isCard() ? AccountType.CREDIT_CARD : AccountType.BANK);
        t.setDirection(truth.direction());
        t.setCardPayment(a.isCard() && truth.direction() == Direction.CREDIT);
        t.setSample(flat);
        // Sanity check: a format that can't read back its own source would only cause confusion.
        Matcher self = Pattern.compile(t.getPattern(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(flat);
        if (!self.matches() || SmsParser.toAmount(self.group("amt")).compareTo(truth.amount()) != 0) return Optional.empty();
        return Optional.of(t);
    }

    private static void add(List<int[]> spans, int[] span, int kind) {
        for (int[] s : spans) if (span[0] < s[1] && s[0] < span[1]) return; // overlaps something already claimed
        spans.add(new int[]{span[0], span[1], kind});
    }

    private static void shape(SmsTemplate t, FormatShape f) {
        t.setSkeleton(f.skeleton());
        t.setPattern(f.pattern());
        t.setParts(f.encode());
    }

    /**
     * Fixed wording between slots. Words are matched literally; anything containing a digit (dates, times, phone
     * numbers) and month/day names become wildcards, because they change from message to message.
     */
    private static void literal(String chunk, FormatShape.Builder shape) {
        Matcher m = Pattern.compile("\\s+|\\S+").matcher(chunk);
        while (m.find()) {
            String tok = m.group();
            if (tok.isBlank()) {
                shape.space();
            } else if (MONTH.matcher(tok).matches()) {
                shape.piece("#", "\\S+");
            } else if (tok.chars().anyMatch(Character::isDigit)) {
                // Keep the token's shape (UPI/P2M/<digits>/, dd-mm-yy, hh:mm:ss) but let the digits vary, so a
                // wildcard can never swallow part of a neighbouring slot.
                Matcher part = Pattern.compile("[0-9][0-9,.]*|[A-Za-z]+|[^0-9A-Za-z]+").matcher(tok);
                StringBuilder rx = new StringBuilder();
                while (part.find()) {
                    String g = part.group();
                    if (Character.isDigit(g.charAt(0))) rx.append("[0-9][0-9,.]*");
                    else if (MONTH.matcher(g).matches()) rx.append("[A-Za-z]+");
                    else rx.append(Pattern.quote(g));
                }
                shape.piece("#", rx.toString());
            } else {
                shape.piece(tok.toLowerCase(Locale.ROOT), Pattern.quote(tok));
            }
        }
    }

    public List<SmsTemplate> templates() {
        return templates.findAllByOrderByHitsDescIdAsc();
    }

    @Transactional
    public void forgetTemplate(Long id) {
        templates.deleteById(id);
    }

}
