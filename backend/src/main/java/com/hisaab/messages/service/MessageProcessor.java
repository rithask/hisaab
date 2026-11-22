package com.hisaab.messages.service;

import com.hisaab.common.model.Direction;
import com.hisaab.common.model.Institution;
import com.hisaab.messages.model.Channel;
import com.hisaab.messages.model.EmailStatus;
import com.hisaab.accounts.model.Account;
import com.hisaab.accounts.repository.AccountRepository;
import com.hisaab.common.util.Money;
import com.hisaab.messages.model.EmailContent;
import com.hisaab.messages.model.IncomingEmail;
import com.hisaab.messages.model.InboxMessage;
import com.hisaab.messages.parser.ParsedTransaction;
import com.hisaab.messages.reading.MessageReader;
import com.hisaab.messages.reading.MessageReading;
import com.hisaab.messages.repository.InboxMessageRepository;
import com.hisaab.statements.model.StatementFile;
import com.hisaab.statements.repository.StatementFileRepository;
import com.hisaab.statements.service.StatementSavedEvent;
import com.hisaab.statements.service.StatementStorage;
import com.hisaab.transactions.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Takes messages in (alert emails, statement emails, bank SMS), keeps every one in raw_email so it can be read
 * again later, and hands the reading to {@link MessageReader}. Teaching lives in {@link MessageTeaching}.
 */
@Service
public class MessageProcessor {
    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);

    private final InboxMessageRepository emails;
    private final AccountRepository accounts;
    private final StatementFileRepository statements;
    private final StatementStorage storage;
    private final MessageReader reader;
    private final ApplicationEventPublisher events;
    private final SenderTrust trust;

    public MessageProcessor(InboxMessageRepository emails, AccountRepository accounts, StatementFileRepository statements, StatementStorage storage,
                            MessageReader reader, ApplicationEventPublisher events, SenderTrust trust) {
        this.emails = emails;
        this.accounts = accounts;
        this.statements = statements;
        this.storage = storage;
        this.reader = reader;
        this.events = events;
        this.trust = trust;
    }

    /** @return false if this message was already seen */
    @Transactional
    public boolean process(IncomingEmail in) {
        if (emails.existsByMessageId(in.messageId())) return false;
        InboxMessage raw = newEmail(in, EmailStatus.NEEDS_REVIEW); // provisional, decided below
        raw.setSenderCheck(in.senderCheck());
        emails.save(raw);

        EmailContent content = new EmailContent(in.from(), in.subject(), in.receivedAt(), in.text());
        List<IncomingEmail.Attachment> pdfs = in.attachments().stream().filter(IncomingEmail.Attachment::isPdf).toList();
        if (!pdfs.isEmpty() && content.haystack().contains("statement")) {
            if (SenderTrust.FAIL.equals(in.senderCheck())) {
                raw.setHold(SenderTrust.UNVERIFIED_SENDER);
                raw.setNote("Your mail provider couldn't confirm this really came from " + SenderTrust.domainOf(in.from())
                        + ", so its statement wasn't saved.");
                log.warn("Statement email {} held: sender {} not confirmed by the mail provider", raw.getId(), SenderTrust.domainOf(in.from()));
                return true;
            }
            saveStatements(raw, content, pdfs);
            log.info("Email {} from {}: {} statement PDF(s), {}", raw.getId(), SenderTrust.domainOf(in.from()), pdfs.size(), raw.getStatus());
            return true;
        }
        reader.read(raw, content);
        log.info("Email {} from {}: {}{}", raw.getId(), SenderTrust.domainOf(in.from()), raw.getStatus(), raw.getHold() == null ? "" : " (held: " + raw.getHold() + ")");
        return true;
    }

    /** Keep a record of an email whose processing blew up, so it shows in the review list instead of vanishing. */
    @Transactional
    public void recordFailure(IncomingEmail in, Exception error) {
        if (emails.existsByMessageId(in.messageId())) return;
        InboxMessage raw = newEmail(in, EmailStatus.ERROR);
        raw.setNote("Couldn't process it: " + error.getMessage());
        emails.save(raw);
    }

    private static InboxMessage newEmail(IncomingEmail in, EmailStatus status) {
        InboxMessage raw = new InboxMessage();
        raw.setMessageId(in.messageId());
        raw.setFromAddress(trim(in.from(), 300));
        raw.setSubject(trim(in.subject(), 1000));
        raw.setReceivedAt(in.receivedAt());
        raw.setBodyText(in.text());
        raw.setStatus(status);
        return raw;
    }

    /** One line saying what was recorded, e.g. "₹500.00 spent · Zomato · HDFC card · Food & Dining". */
    public static String summary(Transaction transaction) {
        java.text.NumberFormat inr = java.text.NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));
        String verb = transaction.getDirection() == Direction.DEBIT ? (transaction.isTransfer() ? "moved" : "spent")
                : "received";
        StringBuilder s = new StringBuilder(inr.format(transaction.getAmount())).append(' ').append(verb);
        if (transaction.getCounterparty() != null) s.append(transaction.getDirection() == Direction.DEBIT ? " · " : " from ").append(transaction.getCounterparty());
        s.append(" · ").append(transaction.getAccount().getName());
        if (transaction.isTransfer()) s.append(" · transfer");
        else if (transaction.getCategory() != null) s.append(" · ").append(transaction.getCategory().getName());
        return s.toString();
    }

    /** Outcome of an SMS submission, returned to the caller (e.g. an iPhone Shortcut showing a notification). */
    public record SmsOutcome(InboxMessage message, Transaction txn, ParsedTransaction parsed, boolean alreadySeen) {}

    /**
     * Records a bank SMS and turns it into a transaction when it is one. The same text submitted twice is
     * recognised and not counted again; a payment already recorded from its alert email is not double counted.
     */
    @Transactional
    public SmsOutcome processSms(String sender, String text, Instant receivedAt) {
        String body = text.strip();
        String id = "sms:" + sha256((sender == null ? "" : sender.strip().toUpperCase(Locale.ROOT)) + "\n" + body.replaceAll("\\s+", " "));
        Optional<InboxMessage> seen = emails.findByMessageId(id);
        if (seen.isPresent()) {
            log.info("SMS from {}: duplicate of message {}", sender == null ? "-" : sender.strip(), seen.get().getId());
            return new SmsOutcome(seen.get(), null, null, true);
        }

        InboxMessage raw = new InboxMessage();
        raw.setMessageId(id);
        raw.setChannel(Channel.SMS);
        raw.setSender(trim(sender == null ? null : sender.strip(), 100));
        raw.setFromAddress(raw.getSender());
        raw.setSubject("SMS" + (raw.getSender() == null ? "" : " from " + raw.getSender()));
        raw.setReceivedAt(receivedAt == null ? Instant.now() : receivedAt);
        raw.setBodyText(body);
        raw.setStatus(EmailStatus.NEEDS_REVIEW);
        emails.save(raw);
        MessageReading reading = reader.read(raw);
        log.info("SMS {} from {}: {}{}", raw.getId(), raw.getSender() == null ? "-" : raw.getSender(), raw.getStatus(),
                reading.txn() == null ? "" : " (transaction " + reading.txn().getId() + ")");
        return new SmsOutcome(raw, reading.txn(), reading.parsed(), false);
    }

    private static String sha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Re-run parsing on a stored email (e.g. after adding the missing account). */
    @Transactional
    public InboxMessage reprocess(InboxMessage raw) {
        if (raw.getStatus() == EmailStatus.PARSED || raw.getStatus() == EmailStatus.STATEMENT) return raw;
        reader.read(raw);
        return raw;
    }

    /**
     * I've said alerts from this domain are genuine: remember it and record the emails from it that were held.
     *
     * @return how many held emails are now recorded
     */
    @Transactional
    public int trustSender(String domain) {
        trust.trust(domain);
        int n = 0;
        for (InboxMessage e : emails.findByStatus(EmailStatus.NEEDS_REVIEW)) {
            if (!SenderTrust.UNKNOWN_SENDER.equals(e.getHold())) continue;
            String d = SenderTrust.domainOf(e.getFromAddress());
            if (d == null || !(d.equals(domain) || d.endsWith("." + domain))) continue;
            reprocess(e);
            if (e.getStatus() == EmailStatus.PARSED || e.getStatus() == EmailStatus.DUPLICATE) n++;
        }
        return n;
    }

    private void saveStatements(InboxMessage raw, EmailContent content, List<IncomingEmail.Attachment> pdfs) {
        Account account = guessStatementAccount(content);
        StringBuilder note = new StringBuilder();
        for (IncomingEmail.Attachment attachment : pdfs) {
            try {
                Path path = storage.save(account, raw.getReceivedAt(), attachment.fileName(), attachment.data());
                StatementFile statementFile = new StatementFile();
                statementFile.setAccount(account);
                statementFile.setRawEmail(raw);
                statementFile.setFileName(attachment.fileName() == null ? path.getFileName().toString()
                        : attachment.fileName());
                statementFile.setStoredPath(storage.toStored(path));
                statementFile.setSizeBytes(attachment.data().length);
                statementFile.setReceivedAt(raw.getReceivedAt());
                statementFile.setPeriodLabel(StatementStorage.PERIOD.withZone(Money.ZONE).format(raw.getReceivedAt()));
                statements.save(statementFile);
                events.publishEvent(new StatementSavedEvent(statementFile.getId()));
            } catch (Exception e) {
                log.warn("Couldn't save statement {} from email {}: {}", attachment.fileName(), raw.getId(),
                        e.toString());
                note.append("Failed to save ").append(attachment.fileName()).append(": ").append(e.getMessage()).append('\n');
            }
        }
        raw.setStatus(note.isEmpty() ? EmailStatus.STATEMENT : EmailStatus.ERROR);
        raw.setNote(note.isEmpty() ? null : note.toString());
    }

    private Account guessStatementAccount(EmailContent content) {
        String hay = content.haystack();
        List<Account> all = accounts.findByArchivedFalseOrderByTypeAscNameAsc();
        List<Account> byLast4 = all.stream().filter(a -> a.getLast4() != null && hay.contains(a.getLast4())).toList();
        if (byLast4.size() == 1) return byLast4.get(0);
        for (Institution inst : Institution.values()) {
            if (inst == Institution.OTHER || !hay.contains(inst.name().toLowerCase(Locale.ROOT))) continue;
            List<Account> byInst = all.stream().filter(a -> a.getInstitution() == inst).toList();
            if (byInst.size() == 1) return byInst.get(0);
            List<Account> cards = byInst.stream().filter(Account::isCard).toList();
            if (cards.size() == 1 && hay.contains("credit card")) return cards.get(0);
        }
        return null;
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
