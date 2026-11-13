package com.hisaab.web;

import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.EmailStatus;
import com.hisaab.domain.Txn;
import com.hisaab.mail.EmailProcessor;
import com.hisaab.mail.EmailProcessor.SmsOutcome;
import com.hisaab.mail.parser.ParsedTransaction;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Pasting a bank SMS in the app (Inbox → Paste SMS; owner session only). Phones and other apps send SMS with a
 * Send SMS token to {@code POST /api/v1/sms} instead (DataApiController).
 */
@RestController
@RequestMapping("/api/sms")
public class SmsController {
    static final int MAX_LENGTH = 2000;

    private final EmailProcessor processor;

    public SmsController(EmailProcessor processor) {
        this.processor = processor;
    }

    public record SmsIn(String text, String sender) {}

    public record TxnSummary(Long id, Direction direction, BigDecimal amount, String currency, String account,
                             String counterparty, String category, Instant occurredAt) {}

    public record ParsedSummary(String bank, String accountType, Direction direction, BigDecimal amount, String currency,
                                String last4, LocalDate date, LocalTime time, String counterparty, String reference,
                                BigDecimal balance) {}

    /** {@code status} is PARSED, DUPLICATE, NEEDS_REVIEW, IGNORED or ERROR; {@code message} is a one-line summary. */
    public record SmsResult(String status, String message, Long messageId, TxnSummary transaction, ParsedSummary parsed) {}

    @PostMapping
    public SmsResult paste(@RequestBody SmsIn in) {
        return ingest(in.sender(), in.text());
    }

    private SmsResult ingest(String sender, String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("SMS text is empty (send it as \"text\")");
        if (text.length() > MAX_LENGTH) throw new IllegalArgumentException("SMS text is too long (max " + MAX_LENGTH + " characters)");
        if (sender != null && sender.length() > 100) sender = sender.substring(0, 100);
        SmsOutcome o = processor.processSms(sender, text, Instant.now());

        if (o.alreadySeen()) {
            return new SmsResult(EmailStatus.DUPLICATE.name(), "Already received this SMS", o.message().getId(), null, null);
        }
        EmailStatus st = o.message().getStatus();
        Txn t = o.txn();
        String msg;
        if (t != null) {
            msg = EmailProcessor.summary(t);
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
}
