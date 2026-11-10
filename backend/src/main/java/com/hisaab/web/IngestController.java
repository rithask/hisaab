package com.hisaab.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.EmailStatus;
import com.hisaab.domain.Txn;
import com.hisaab.mail.EmailProcessor;
import com.hisaab.mail.EmailProcessor.SmsOutcome;
import com.hisaab.mail.parser.ParsedTransaction;
import com.hisaab.service.ApiTokenService;
import com.hisaab.service.Money;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * Bank SMS ingest. An automation on the phone (iOS Shortcuts "Message" trigger, an Android SMS forwarder) POSTs
 * the text with the API token; the owner can also paste one from the app (session auth).
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {
    static final int MAX_LENGTH = 2000;

    private final EmailProcessor processor;
    private final ApiTokenService tokens;

    public IngestController(EmailProcessor processor, ApiTokenService tokens) {
        this.processor = processor;
        this.tokens = tokens;
    }

    public record SmsIn(@JsonAlias({"message", "body", "content", "sms"}) String text,
                        @JsonAlias({"from", "address", "phone", "senderId"}) String sender,
                        @JsonAlias({"date", "timestamp", "received", "time"}) String receivedAt) {}

    public record TxnSummary(Long id, Direction direction, BigDecimal amount, String currency, String account,
                             String counterparty, String category, Instant occurredAt) {}

    public record ParsedSummary(String bank, String accountType, Direction direction, BigDecimal amount, String currency,
                                String last4, LocalDate date, LocalTime time, String counterparty, String reference,
                                BigDecimal balance) {}

    /** {@code status} is PARSED, DUPLICATE, NEEDS_REVIEW, IGNORED or ERROR; {@code message} is a one-line summary. */
    public record SmsResult(String status, String message, Long messageId, TxnSummary transaction, ParsedSummary parsed) {}

    @PostMapping(value = "/sms", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SmsResult smsJson(@RequestBody SmsIn in) {
        return ingest(in.sender(), in.text(), in.receivedAt());
    }

    /** Plain-text body for the simplest clients: {@code curl -H 'Authorization: Bearer …' --data-binary @sms.txt}. */
    @PostMapping(value = "/sms", consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.ALL_VALUE})
    public SmsResult smsText(@RequestBody String text, @RequestParam(required = false) String sender,
                             @RequestParam(required = false) String receivedAt) {
        return ingest(sender, text, receivedAt);
    }

    private SmsResult ingest(String sender, String text, String receivedAt) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("SMS text is empty (send it as \"text\")");
        if (text.length() > MAX_LENGTH) throw new IllegalArgumentException("SMS text is too long (max " + MAX_LENGTH + " characters)");
        if (sender != null && sender.length() > 100) sender = sender.substring(0, 100);
        SmsOutcome o = processor.processSms(sender, text, parseInstant(receivedAt));

        if (o.alreadySeen()) {
            return new SmsResult(EmailStatus.DUPLICATE.name(), "Already received this SMS", o.message().getId(), null, null);
        }
        EmailStatus st = o.message().getStatus();
        Txn t = o.txn();
        String msg;
        if (t != null) {
            msg = summary(t);
        } else {
            msg = o.message().getNote();
        }
        TxnSummary ts = t == null ? null : new TxnSummary(t.getId(), t.getDirection(), t.getAmount(),
                t.getOriginalCurrency() == null ? "INR" : t.getOriginalCurrency(), t.getAccount().getName(), t.getCounterparty(),
                t.getCategory() == null ? null : t.getCategory().getName(), t.getOccurredAt());
        ParsedTransaction p = o.parsed();
        ParsedSummary ps = p == null ? null : new ParsedSummary(p.institution().name(), p.accountType().name(), p.direction(),
                p.amount(), p.currency(), p.last4(), p.date(), p.time(), p.counterparty(), p.reference(), p.balance());
        return new SmsResult(st.name(), msg, o.message().getId(), ts, ps);
    }

    private static String summary(Txn t) {
        NumberFormat inr = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));
        String verb = t.getDirection() == Direction.DEBIT ? (t.isTransfer() ? "moved" : "spent") : "received";
        StringBuilder s = new StringBuilder(inr.format(t.getAmount())).append(' ').append(verb);
        if (t.getCounterparty() != null) s.append(t.getDirection() == Direction.DEBIT ? " · " : " from ").append(t.getCounterparty());
        s.append(" · ").append(t.getAccount().getName());
        if (t.isTransfer()) s.append(" · transfer");
        else if (t.getCategory() != null) s.append(" · ").append(t.getCategory().getName());
        return s.toString();
    }

    /** Accepts ISO-8601 (with or without offset; local times are IST) or epoch seconds/millis. Future times clamp to now. */
    static Instant parseInstant(String v) {
        Instant now = Instant.now();
        if (v == null || v.isBlank()) return now;
        String s = v.trim();
        Instant at = null;
        try {
            if (s.matches("\\d{9,13}")) {
                long n = Long.parseLong(s);
                at = s.length() > 10 ? Instant.ofEpochMilli(n) : Instant.ofEpochSecond(n);
            } else if (s.endsWith("Z")) {
                at = Instant.parse(s);
            } else if (s.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                at = OffsetDateTime.parse(s.replaceFirst("([+-]\\d{2})(\\d{2})$", "$1:$2")).toInstant();
            } else {
                at = java.time.LocalDateTime.parse(s.replace(' ', 'T')).atZone(Money.ZONE).toInstant();
            }
        } catch (RuntimeException e) {
            try {
                at = ZonedDateTime.parse(s).toInstant();
            } catch (RuntimeException ignored) {
                return now; // unparseable: the SMS is being forwarded right now anyway
            }
        }
        return at.isAfter(now.plusSeconds(300)) ? now : at;
    }

    // ---- token management (owner session only) ---------------------------------------------------------------

    public record TokenCreated(String token, ApiTokenService.Status status) {}

    @GetMapping("/token")
    public ApiTokenService.Status tokenStatus() {
        return tokens.status();
    }

    @PostMapping("/token")
    public TokenCreated rotateToken() {
        String token = tokens.rotate();
        return new TokenCreated(token, tokens.status());
    }

    @DeleteMapping("/token")
    public ApiTokenService.Status revokeToken() {
        tokens.revoke();
        return tokens.status();
    }
}
