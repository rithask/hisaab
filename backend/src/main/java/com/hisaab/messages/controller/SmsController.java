package com.hisaab.messages.controller;

import com.hisaab.messages.dto.SmsParsedResponse;
import com.hisaab.messages.dto.SmsRequest;
import com.hisaab.messages.dto.SmsResponse;
import com.hisaab.messages.dto.SmsTransactionResponse;
import com.hisaab.messages.model.EmailStatus;
import com.hisaab.messages.parser.ParsedTransaction;
import com.hisaab.messages.service.MessageProcessor;
import com.hisaab.messages.service.MessageProcessor.SmsOutcome;
import com.hisaab.transactions.model.Transaction;
import org.springframework.web.bind.annotation.*;

import com.hisaab.common.exception.BadRequestException;
import java.time.Instant;

/**
 * Pasting a bank SMS in the app (Inbox → Paste SMS; owner session only). Phones and other apps send SMS with a
 * Send SMS token to {@code POST /api/v1/sms} instead (DataApiController).
 */
@RestController
@RequestMapping("/api/sms")
public class SmsController {
    static final int MAX_LENGTH = 2000;

    private final MessageProcessor processor;

    public SmsController(MessageProcessor processor) {
        this.processor = processor;
    }

    @PostMapping
    public SmsResponse paste(@RequestBody SmsRequest in) {
        return ingest(in.sender(), in.text());
    }

    private SmsResponse ingest(String sender, String text) {
        if (text == null || text.isBlank()) throw new BadRequestException("SMS text is empty (send it as \"text\")");
        if (text.length() > MAX_LENGTH) throw new BadRequestException("SMS text is too long (max " + MAX_LENGTH + " characters)");
        if (sender != null && sender.length() > 100) sender = sender.substring(0, 100);
        SmsOutcome smsOutcome = processor.processSms(sender, text, Instant.now());

        if (smsOutcome.alreadySeen()) {
            return new SmsResponse(EmailStatus.DUPLICATE.name(), "Already received this SMS", smsOutcome.message().getId(), null, null);
        }
        EmailStatus st = smsOutcome.message().getStatus();
        Transaction transaction = smsOutcome.txn();
        String msg;
        if (transaction != null) {
            msg = MessageProcessor.summary(transaction);
        } else {
            msg = smsOutcome.message().getNote();
        }
        SmsTransactionResponse ts = transaction == null ? null : new SmsTransactionResponse(transaction.getId(), transaction.getDirection(), transaction.getAmount(),
                transaction.getOriginalCurrency() == null ? "INR" : transaction.getOriginalCurrency(), transaction.getAccount().getName(), transaction.getCounterparty(),
                transaction.getCategory() == null ? null : transaction.getCategory().getName(),
                        transaction.getOccurredAt());
        ParsedTransaction p = smsOutcome.parsed();
        SmsParsedResponse ps = p == null ? null : new SmsParsedResponse(p.institution().name(), p.accountType().name(), p.direction(),
                p.amount(), p.currency(), p.last4(), p.date(), p.time(), p.counterparty(), p.reference(), p.balance());
        return new SmsResponse(st.name(), msg, smsOutcome.message().getId(), ts, ps);
    }
}
