package com.hisaab.messages.service;

import com.hisaab.common.model.AccountType;
import com.hisaab.common.model.Direction;
import com.hisaab.common.model.Institution;
import com.hisaab.messages.model.Channel;
import com.hisaab.messages.model.LearnedFormat;
import com.hisaab.messages.parser.ParsedTransaction;
import com.hisaab.messages.parser.SmsParser;
import com.hisaab.messages.repository.LearnedFormatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reads a message with a format I taught (see {@link FormatLearningService}), when one fits its wording exactly: the
 * amount, payee, account digits, reference and balance come from the format's slots, not from guessing.
 */
@Service
public class LearnedFormatReader {
    private static final Logger log = LoggerFactory.getLogger(LearnedFormatReader.class);

    /** An amount as banks write it: 1,250.75 */
    static final String NUM = "[0-9][0-9,]*(?:\\.[0-9]{1,2})?";
    static final Pattern NUMBER = Pattern.compile(NUM);

    private final LearnedFormatRepository templates;

    public LearnedFormatReader(LearnedFormatRepository templates) {
        this.templates = templates;
    }

    /** @param txn what the format read; null when the format says the message isn't a transaction */
    public record Match(ParsedTransaction txn, LearnedFormat template) {
        public boolean skip() {
            return txn == null;
        }
    }

    /** What a learned format reads from a message: the fields the safety net compares (all null for a skip). */
    record Read(LearnedFormat template, Direction direction, BigDecimal amount, Institution institution, String payee, Matcher m) {
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
        LearnedFormat format = r.get().template();
        format.setHits(format.getHits() + 1);
        format.setLastUsedAt(Instant.now());
        if (r.get().skip()) return Optional.of(new Match(null, format));
        Matcher matcher = r.get().m();
        SmsParser.When when = SmsParser.findWhen(flat, receivedAt);
        ParsedTransaction parsed = new ParsedTransaction(format.getInstitution(), format.getAccountType(), format.getDirection(), r.get().amount(), "INR",
                group(format, matcher, "l4"), when.date(), when.time(), r.get().payee(), group(format, matcher, "ref"),
                format.getAccountType() == AccountType.CREDIT_CARD || group(format, matcher, "bal") == null ? null : SmsParser.toAmount(group(format, matcher, "bal")),
                format.isCardPayment(), null);
        return Optional.of(new Match(parsed, format));
    }

    /** The first of these formats (in the order given) that fits the whole message. */
    static Optional<Read> read(List<LearnedFormat> set, String flat) {
        // "Not a transaction" formats are checked first, then the ones that read transactions; within each, the
        // newest lesson wins (the safety net already made sure it doesn't break older ones). Paused ones aren't used.
        Comparator<LearnedFormat> newestFirst = Comparator.comparing((LearnedFormat t) -> t.getId() == null ? Long.MAX_VALUE : t.getId()).reversed();
        List<LearnedFormat> ordered = new ArrayList<>(set.stream().filter(t -> t.isSkip() && !t.isPaused()).sorted(newestFirst).toList());
        set.stream().filter(t -> !t.isSkip() && !t.isPaused()).sorted(newestFirst).forEach(ordered::add);
        for (LearnedFormat format : ordered) {
            Matcher m;
            try {
                m = Pattern.compile(format.getPattern(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(flat);
            } catch (PatternSyntaxException e) {
                log.warn("Learned SMS format {} is unusable: {}", format.getId(), e.getMessage());
                continue;
            }
            if (!m.matches()) continue;
            if (format.isSkip()) return Optional.of(new Read(format, null, null, null, null, m));
            BigDecimal amount;
            try {
                amount = SmsParser.toAmount(m.group("amt"));
            } catch (RuntimeException e) {
                continue;
            }
            if (amount.signum() <= 0) continue;
            return Optional.of(new Read(format, format.getDirection(), amount, format.getInstitution(), clean(group(format, m, "payee")), m));
        }
        return Optional.empty();
    }

    private static String group(LearnedFormat format, Matcher matcher, String name) {
        return format.getPattern().contains("(?<" + name + ">") ? matcher.group(name) : null;
    }

    /** A paused format that fits this message, if any (its messages wait for review instead). */
    public Optional<LearnedFormat> pausedFor(Channel channel, String text) {
        String flat = flat(text);
        return templates.findByChannelOrderByHitsDescIdAsc(channel).stream().filter(LearnedFormat::isPaused)
                .filter(t -> read(List.of(withoutPause(t)), flat).isPresent()).findFirst();
    }

    static LearnedFormat withoutPause(LearnedFormat t) {
        if (!t.isPaused()) return t;
        LearnedFormat c = new LearnedFormat();
        c.setId(t.getId());
        c.setPattern(t.getPattern());
        c.setKind(t.getKind());
        c.setDirection(t.getDirection());
        c.setInstitution(t.getInstitution());
        return c;
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

    static String flat(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    static String clean(String s) {
        if (s == null) return null;
        String v = s.replaceAll("\\s+", " ").trim().replaceAll("[.,;:]+$", "").trim();
        return v.isEmpty() ? null : v;
    }}
