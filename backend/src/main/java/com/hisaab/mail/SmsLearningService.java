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
import com.hisaab.domain.Enums.Channel;
import com.hisaab.domain.LearnedExample;
import com.hisaab.domain.RawEmail;
import com.hisaab.repo.AccountHintRepo;
import com.hisaab.repo.LearnedExampleRepo;
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
    private final LearnedExampleRepo examples;

    public SmsLearningService(SmsTemplateRepo templates, AccountHintRepo hints, SmsSenderRepo senders, LearnedExampleRepo examples) {
        this.examples = examples;
        this.templates = templates;
        this.hints = hints;
        this.senders = senders;
    }

    // ---- formats --------------------------------------------------------------------------------------------

    /** @param txn what the format read; null when the format says the message isn't a transaction */
    public record Match(ParsedTransaction txn, SmsTemplate template) {
        public boolean skip() {
            return txn == null;
        }
    }

    /** What a learned format reads from a message: the fields the safety net compares (all null for a skip). */
    record Read(SmsTemplate template, Direction direction, BigDecimal amount, Institution institution, String payee, Matcher m) {
        boolean skip() {
            return template.isSkip();
        }
    }

    /** Reads the SMS with a learned format, if one fits its wording exactly. */
    @Transactional
    public Optional<Match> match(String text, Instant receivedAt) {
        return match(Channel.SMS, text, receivedAt);
    }

    /** Reads the message with a format learned for its channel, if one fits its wording exactly. */
    @Transactional
    public Optional<Match> match(Channel channel, String text, Instant receivedAt) {
        String flat = flat(text);
        Optional<Read> r = read(templates.findByChannelOrderByHitsDescIdAsc(channel), flat);
        if (r.isEmpty()) return Optional.empty();
        SmsTemplate t = r.get().template();
        t.setHits(t.getHits() + 1);
        t.setLastUsedAt(Instant.now());
        if (r.get().skip()) return Optional.of(new Match(null, t));
        Matcher m = r.get().m();
        SmsParser.When w = SmsParser.findWhen(flat, receivedAt);
        ParsedTransaction p = new ParsedTransaction(t.getInstitution(), t.getAccountType(), t.getDirection(), r.get().amount(), "INR",
                group(t, m, "l4"), w.date(), w.time(), r.get().payee(), group(t, m, "ref"),
                t.getAccountType() == AccountType.CREDIT_CARD || group(t, m, "bal") == null ? null : SmsParser.toAmount(group(t, m, "bal")),
                t.isCardPayment(), null);
        return Optional.of(new Match(p, t));
    }

    /** The first of these formats (in the order given) that fits the whole message. */
    static Optional<Read> read(List<SmsTemplate> set, String flat) {
        // "Not a transaction" formats are checked first, then the ones that read transactions; within each, the
        // newest lesson wins (the safety net already made sure it doesn't break older ones). Paused ones aren't used.
        Comparator<SmsTemplate> newestFirst = Comparator.comparing((SmsTemplate t) -> t.getId() == null ? Long.MAX_VALUE : t.getId()).reversed();
        List<SmsTemplate> ordered = new ArrayList<>(set.stream().filter(t -> t.isSkip() && !t.isPaused()).sorted(newestFirst).toList());
        set.stream().filter(t -> !t.isSkip() && !t.isPaused()).sorted(newestFirst).forEach(ordered::add);
        for (SmsTemplate t : ordered) {
            Matcher m;
            try {
                m = Pattern.compile(t.getPattern(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(flat);
            } catch (PatternSyntaxException e) {
                log.warn("Learned SMS format {} is unusable: {}", t.getId(), e.getMessage());
                continue;
            }
            if (!m.matches()) continue;
            if (t.isSkip()) return Optional.of(new Read(t, null, null, null, null, m));
            BigDecimal amount;
            try {
                amount = SmsParser.toAmount(m.group("amt"));
            } catch (RuntimeException e) {
                continue;
            }
            if (amount.signum() <= 0) continue;
            return Optional.of(new Read(t, t.getDirection(), amount, t.getInstitution(), clean(group(t, m, "payee")), m));
        }
        return Optional.empty();
    }

    private static String group(SmsTemplate t, Matcher m, String name) {
        return t.getPattern().contains("(?<" + name + ">") ? m.group(name) : null;
    }

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
        Matcher m = NUMBER.matcher(flat).region(s[0], s[1]);
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
        String flat = flat(text);
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
                .filter(t -> read(List.of(t), flat).isPresent()).toList();
        after.removeAll(overruled);
        after.add(candidate);
        Check check = check(channel, before, after, flat, false);
        if (check.blocking() > 0) return new LearnResult(Optional.empty(), check.blocking());

        // A message of a kind already learned in slightly different wording: one format for both, if that's safe.
        if (same.isEmpty()) {
            Optional<LearnResult> merged = mergeInto(channel, before, overruled, candidate, flat, text, truth, source);
            if (merged.isPresent()) return merged.get();
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
        return new LearnResult(Optional.of(saved), 0);
    }

    /**
     * "Not a transaction": messages worded like this one are skipped from now on. Digits and month names vary, the
     * rest of the wording must match. Refused when it would skip a message I taught as a transaction.
     */
    @Transactional
    public LearnResult skip(Channel channel, String text, RawEmail source) {
        String flat = flat(text);
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
        if (check.blocking() > 0) return new LearnResult(Optional.empty(), check.blocking());
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
            if (flat(old.getText()).equals(flat)) examples.delete(old);
        }
        LearnedExample e = new LearnedExample();
        e.setRawEmail(source);
        e.setChannel(channel);
        e.setText(text.strip());
        e.setKind(LearnedExample.SKIP);
        examples.save(e);
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
        String flat = flat(text);
        List<SmsTemplate> before = templates.findByChannelOrderByHitsDescIdAsc(channel);
        if (read(before, flat).isPresent()) return LearnResult.none();
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
        examples.findByChannel(channel).stream().filter(e -> flat(e.getText()).equals(flat))
                .forEach(e -> e.setOrigin(SmsTemplate.AUTO));
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
        Optional<Read> r = read(List.of(withoutPause(t)), flat(text));
        boolean wrong;
        if (truth == null) {
            wrong = r.isEmpty() || !r.get().skip();
        } else {
            wrong = !right(lessonOf(flat(text), truth), r);
        }
        if (!wrong) return false;
        if (t.isAuto()) {
            // learned automatically and now corrected: it goes
            templates.delete(t);
            return false;
        }
        t.setCorrections(t.getCorrections() + 1);
        if (t.getCorrections() >= PAUSE_AFTER && !t.isPaused()) {
            t.setPaused(true);
            return true;
        }
        return false;
    }

    private static SmsTemplate withoutPause(SmsTemplate t) {
        if (!t.isPaused()) return t;
        SmsTemplate c = new SmsTemplate();
        c.setId(t.getId());
        c.setPattern(t.getPattern());
        c.setKind(t.getKind());
        c.setDirection(t.getDirection());
        c.setInstitution(t.getInstitution());
        return c;
    }

    /** A paused format that fits this message, if any (its messages wait for review instead). */
    public Optional<SmsTemplate> pausedFor(Channel channel, String text) {
        String flat = flat(text);
        return templates.findByChannelOrderByHitsDescIdAsc(channel).stream().filter(SmsTemplate::isPaused)
                .filter(t -> read(List.of(withoutPause(t)), flat).isPresent()).findFirst();
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
            if (!right(lesson, read(List.of(m), flat))) continue;
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
        e.setPayee(marked != null ? clean(flat.substring(marked[0], marked[1])) : null);
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
        void apply(LearnedExampleRepo repo) {
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
        List<SmsTemplate> was = before.stream().map(SmsLearningService::withoutPause).toList();
        for (LearnedExample e : examples.findByChannel(channel)) {
            String f = flat(e.getText());
            if (f.equals(exceptText)) continue;
            Optional<Read> now = read(after, f);
            boolean broken = right(e, read(was, f)) && !right(e, now);
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

    static boolean right(LearnedExample e, Optional<Read> r) {
        if (LearnedExample.SKIP.equals(e.getKind())) return r.isPresent() && r.get().skip();
        if (r.isEmpty() || r.get().skip()) return false;
        Read x = r.get();
        return x.direction() == e.getDirection() && x.amount().compareTo(e.getAmount()) == 0
                && x.institution() == e.getInstitution()
                && (e.getPayee() == null || e.getPayee().equalsIgnoreCase(x.payee()));
    }

    /** Keeps the message and its answer as a lesson, replacing any earlier lesson on the same text. */
    private void remember(Channel channel, String flat, String text, Truth truth, RawEmail source) {
        for (LearnedExample old : examples.findByChannel(channel)) {
            if (flat(old.getText()).equals(flat)) examples.delete(old);
        }
        LearnedExample e = new LearnedExample();
        e.setRawEmail(source);
        e.setChannel(channel);
        e.setText(text.strip());
        e.setDirection(truth.direction());
        e.setAmount(truth.amount().setScale(2, java.math.RoundingMode.HALF_UP));
        e.setInstitution(truth.account().getInstitution());
        e.setAccountType(truth.account().isCard() ? AccountType.CREDIT_CARD : AccountType.BANK);
        int[] marked = span(truth.payeeAt(), flat);
        String payee = marked != null ? clean(flat.substring(marked[0], marked[1]))
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
        if (amt == null) amt = findAmount(flat, truth.amount());
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
                case 0 -> shape.piece("<AMT>", "(?<amt>" + NUM + ")");
                case 1 -> shape.piece("<PAYEE>", "(?<payee>.+?)");
                case 2 -> shape.piece("<L4>", "(?<l4>\\d{4})");
                case 3 -> shape.piece("<REF>", "(?<ref>[A-Za-z0-9]+)");
                default -> shape.piece("<BAL>", "(?<bal>" + NUM + ")");
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
