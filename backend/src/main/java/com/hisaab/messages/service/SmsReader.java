package com.hisaab.messages.service;

import com.hisaab.common.model.Enums.AccountType;
import com.hisaab.common.model.Enums.Channel;
import com.hisaab.common.model.Enums.Direction;
import com.hisaab.common.model.Enums.Institution;
import com.hisaab.messages.model.SmsTemplate;
import com.hisaab.messages.parser.ParsedTransaction;
import com.hisaab.messages.parser.SmsParser;
import com.hisaab.messages.repository.SmsTemplateRepository;
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
 * Reads a message with a format I taught (see {@link SmsLearningService}), when one fits its wording exactly: the
 * amount, payee, account digits, reference and balance come from the format's slots, not from guessing.
 */
@Service
public class SmsReader {
    private static final Logger log = LoggerFactory.getLogger(SmsReader.class);

    /** An amount as banks write it: 1,250.75 */
    static final String NUM = "[0-9][0-9,]*(?:\\.[0-9]{1,2})?";
    static final Pattern NUMBER = Pattern.compile(NUM);

    private final SmsTemplateRepository templates;

    public SmsReader(SmsTemplateRepository templates) {
        this.templates = templates;
    }

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

    /** A paused format that fits this message, if any (its messages wait for review instead). */
    public Optional<SmsTemplate> pausedFor(Channel channel, String text) {
        String flat = flat(text);
        return templates.findByChannelOrderByHitsDescIdAsc(channel).stream().filter(SmsTemplate::isPaused)
                .filter(t -> read(List.of(withoutPause(t)), flat).isPresent()).findFirst();
    }

    static SmsTemplate withoutPause(SmsTemplate t) {
        if (!t.isPaused()) return t;
        SmsTemplate c = new SmsTemplate();
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
