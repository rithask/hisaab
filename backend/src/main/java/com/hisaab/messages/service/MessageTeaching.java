package com.hisaab.messages.service;

import com.hisaab.messages.dto.TeachPreviewResponse;
import com.hisaab.messages.dto.TeachRequest;
import com.hisaab.transactions.dto.TransactionUpdateRequest;
import com.hisaab.common.model.AccountType;
import com.hisaab.common.model.Direction;
import com.hisaab.messages.model.Channel;
import com.hisaab.messages.model.EmailStatus;
import com.hisaab.accounts.model.Account;
import com.hisaab.accounts.repository.AccountRepository;
import com.hisaab.categories.model.Category;
import com.hisaab.categories.repository.CategoryRepository;
import com.hisaab.categories.service.CategorizationService;
import com.hisaab.categories.service.PayeeAliasService;
import com.hisaab.messages.model.InboxMessage;
import com.hisaab.messages.model.LearnedFormat;
import com.hisaab.messages.parser.ParseUtil;
import com.hisaab.messages.parser.ParsedTransaction;
import com.hisaab.messages.parser.SmsParser;
import com.hisaab.messages.reading.MessageAccounts;
import com.hisaab.messages.reading.MessageReader;
import com.hisaab.messages.repository.InboxMessageRepository;
import com.hisaab.transactions.model.Transaction;
import com.hisaab.transactions.service.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hisaab.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What I tell the app about a message: what it really was (teach), or that it isn't a transaction. Each lesson is
 * learned (format, payee name, account link, sender) and the messages still waiting are read again with it.
 */
@Service
public class MessageTeaching {
    private final InboxMessageRepository emails;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final TransactionService txns;
    private final SmsParser smsParser;
    private final FormatLearningService learning;
    private final SenderTrust trust;
    private final PayeeAliasService aliases;
    private final MessageAccounts messageAccounts;
    private final MessageReader reader;
    private final LearnedFormatReader smsReader;
    private final SmsSenders senders;

    public MessageTeaching(InboxMessageRepository emails, AccountRepository accounts, CategoryRepository categories, TransactionService txns,
                           SmsParser smsParser, FormatLearningService learning, SenderTrust trust, PayeeAliasService aliases,
                           MessageAccounts messageAccounts, MessageReader reader, LearnedFormatReader smsReader, SmsSenders senders) {
        this.senders = senders;
        this.smsReader = smsReader;
        this.emails = emails;
        this.accounts = accounts;
        this.categories = categories;
        this.txns = txns;
        this.smsParser = smsParser;
        this.learning = learning;
        this.trust = trust;
        this.aliases = aliases;
        this.messageAccounts = messageAccounts;
        this.reader = reader;
    }

    /** What the parser makes of a stored SMS right now, to pre-fill the correction form. */
    @Transactional(readOnly = true)
    public TeachPreviewResponse preview(InboxMessage raw) {
        ParsedTransaction p = smsReader.match(raw.getChannel(), raw.getBodyText(), raw.getReceivedAt()).map(LearnedFormatReader.Match::txn)
                .orElseGet(() -> smsParser.parse(raw.getSender(), raw.getBodyText(), raw.getReceivedAt()).txn());
        String flat = raw.getBodyText() == null ? "" : raw.getBodyText().replaceAll("\\s+", " ").trim();
        List<Transaction> existing = txns.forMessage(raw);
        Direction direction = p == null ? null : p.direction();
        BigDecimal amount = p == null ? null : p.amount();
        Long accountId = p == null ? null : messageAccounts.learnedOrMatched(p).map(Account::getId).orElse(null);
        String name = p == null ? null : p.counterparty();
        Long categoryId = null;
        if (!existing.isEmpty()) {
            Transaction t = existing.get(0);
            categoryId = t.getCategory() == null || t.isCategorySuggested() ? null : t.getCategory().getId();
            direction = t.getDirection();
            amount = t.getAmount();
            accountId = t.getAccount().getId();
            name = t.getCounterparty();
        }
        // Where those are in the text, so the form can show them marked.
        int[] amountAt = amount == null ? null : LearnedFormatReader.findAmount(flat, amount);
        int[] payeeAt = null;
        for (String v : Arrays.asList(p == null ? null : p.counterparty(), name)) {
            int at = v == null || v.isBlank() ? -1 : flat.toLowerCase(Locale.ROOT).indexOf(v.toLowerCase(Locale.ROOT));
            if (at >= 0) {
                payeeAt = new int[]{at, at + v.length()};
                break;
            }
        }
        return new TeachPreviewResponse(direction, amount, accountId, name, flat, amountAt, payeeAt, categoryId);
    }

    /**
     * @param alsoFixed other waiting messages that could be recorded thanks to what was just learned
     * @param conflicts when no format was learned because of the safety net: how many earlier lessons it would break
     * @param category  the category I picked, if any
     * @param categoryByWording that category was remembered for messages worded like this (it has no payee)
     */
    public record TeachResult(InboxMessage message, Transaction txn, boolean learnedFormat, boolean learnedAccount, int alsoFixed, int conflicts,
                              boolean pausedFormat, boolean mergedFormat, String category, boolean categoryByWording) {}

    /**
     * I say what an SMS or bank email really was. The transaction is recorded (replacing a wrong one if it had been
     * parsed), the wording becomes a learned format, the account link is remembered, and every other message still
     * waiting for review is tried again with the new knowledge. Teaching an email also trusts its sender's domain
     * (not a free mail domain, and not one the provider flagged as forged), since I've said its alerts are real.
     */
    @Transactional
    public TeachResult teach(InboxMessage raw, TeachRequest in) {
        if (raw.getStatus() == EmailStatus.STATEMENT) throw new BadRequestException("Statements are imported, not taught");
        if (in.direction() == null) throw new BadRequestException("Say whether money went out or came in");
        if (in.amount() == null || in.amount().signum() <= 0) throw new BadRequestException("Amount must be positive");
        Account account = accounts.findById(in.accountId() == null ? -1L : in.accountId())
                .orElseThrow(() -> new IllegalArgumentException("Pick the account"));
        Category category = in.categoryId() == null ? null : categories.findById(in.categoryId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown category"));
        if (category != null && category.getKind() != CategorizationService.kindFor(in.direction())) {
            throw new BadRequestException(in.direction() == Direction.DEBIT ? "Pick a spending category for money going out"
                    : "Pick an income category for money coming in");
        }
        String text = raw.getBodyText();
        String flat = text.replaceAll("\\s+", " ").trim();
        // The payee as the bank writes it (what I marked, else what I typed) and the name I want shown for it.
        int[] marked = FormatLearningService.span(in.payeeAt(), flat);
        String name = in.counterparty() == null || in.counterparty().isBlank() ? null : in.counterparty().trim();
        String payee = marked != null ? ParseUtil.clean(flat.substring(marked[0], marked[1])) : name;
        if (payee != null && name != null && !payee.equals(name)) aliases.remember(payee, name);

        // A transaction already recorded from it is fixed in place, so its category, notes, tags and split stay.
        List<Transaction> recorded = txns.forMessage(raw);
        for (Transaction extra : recorded.stream().skip(1).toList()) txns.delete(extra.getId());
        if (recorded.isEmpty()) txns.unpair(raw);

        FormatLearningService.Truth truth = new FormatLearningService.Truth(in.direction(), in.amount(), account, payee, in.amountAt(), marked);
        // the format that read it (if one did) was wrong: count that against it before learning the right one
        LearnedFormat readBy = raw.getTemplate();
        boolean paused = learning.corrected(readBy, text, truth);
        FormatLearningService.LearnResult learned = learning.teach(raw.getChannel(), text, truth, raw);
        // read by the format just learned from now on (it's what a category is remembered for when there's no payee)
        raw.setTemplate(learned.template().orElse(null));
        if (raw.getChannel() == Channel.EMAIL) trustTaughtSender(raw);
        boolean learnedFormat = learned.template().isPresent();

        // If the digits in the text aren't this account's (or there are none), remember what they mean.
        int[] l4 = SmsParser.findLast4(flat);
        String textLast4 = l4 == null ? null : flat.substring(l4[0], l4[1]);
        boolean learnedAccount = false;
        if (textLast4 == null || !textLast4.equals(account.getLast4())) {
            senders.rememberAccount(account.getInstitution(), textLast4, account);
            learnedAccount = true;
        }

        SmsParser.When w = SmsParser.findWhen(flat, raw.getReceivedAt());
        int[] ref = SmsParser.findReference(flat);
        int[] bal = SmsParser.findBalance(flat);
        ParsedTransaction p = new ParsedTransaction(account.getInstitution(),
                account.isCard() ? AccountType.CREDIT_CARD : AccountType.BANK, in.direction(), in.amount().setScale(2, RoundingMode.HALF_UP),
                "INR", account.getLast4(), w.date(), w.time(), payee, ref == null ? null : flat.substring(ref[0], ref[1]),
                bal == null || account.isCard() ? null : SmsParser.toAmount(flat.substring(bal[0], bal[1])),
                account.isCard() && in.direction() == Direction.CREDIT, null);
        Optional<Transaction> created = recorded.isEmpty() ? txns.createFromEmail(p, account, raw)
                : Optional.of(txns.correct(recorded.get(0), p, account));
        boolean sms = raw.getChannel() == Channel.SMS;
        raw.setParser(sms ? "sms-taught" : "email-taught");
        raw.setStatus(created.isPresent() ? EmailStatus.PARSED : EmailStatus.DUPLICATE);
        raw.setNote(created.isPresent() ? null : "Already recorded (from the alert email or an earlier SMS).");
        raw.setHold(null);
        if (sms) senders.rememberSender(raw.getSender(), account.getInstitution());

        // The category I picked, set and learned before the waiting messages are read again, so they get it too.
        // When this one was already recorded from the other channel, it goes on that transaction.
        Optional<Transaction> target = created.isPresent() ? created : txns.pairedWith(raw).stream().findFirst();
        boolean byWording = false;
        if (category != null && target.isPresent()) {
            byWording = txns.update(target.get().getId(), new TransactionUpdateRequest(
                    category.getId(), null, null, null, null, null, null, null, true)).learnedWording();
        }

        int alsoFixed = 0;
        for (EmailStatus st : List.of(EmailStatus.NEEDS_REVIEW, EmailStatus.ERROR, EmailStatus.IGNORED)) {
            for (InboxMessage other : emails.findByStatus(st)) {
                if (other.getChannel() != raw.getChannel() || other.getId().equals(raw.getId())) continue;
                // Messages I chose to ignore are only revisited when a learned format reads them exactly.
                if (st == EmailStatus.IGNORED && smsReader.match(other.getChannel(), other.getBodyText(), other.getReceivedAt()).filter(x -> !x.skip()).isEmpty()) continue;
                reader.read(other);
                if (other.getStatus() == EmailStatus.PARSED) alsoFixed++;
            }
        }
        return new TeachResult(raw, created.orElse(null), learnedFormat, learnedAccount, alsoFixed, learned.conflicts(),
                paused && readBy.isPaused(), learned.merged(), category != null && target.isPresent() ? category.getName() : null, byWording);
    }

    /** @param alsoSkipped other messages waiting for review that are worded the same and were skipped too */
    public record SkipResult(InboxMessage message, boolean learned, int conflicts, int alsoSkipped) {}

    /**
     * I say a message isn't a transaction: it's kept as Ignored, any transaction recorded from it is removed, its
     * wording is learned so later ones are skipped, and waiting messages worded the same are skipped now.
     */
    @Transactional
    public SkipResult notATransaction(InboxMessage raw) {
        if (raw.getStatus() == EmailStatus.STATEMENT) throw new BadRequestException("Statements are imported, not taught");
        learning.corrected(raw.getTemplate(), raw.getBodyText(), null);
        raw.setTemplate(null);
        FormatLearningService.LearnResult r = learning.skip(raw.getChannel(), raw.getBodyText(), raw);
        for (Transaction t : txns.forMessage(raw)) txns.delete(t.getId());
        txns.unpair(raw);
        raw.setStatus(EmailStatus.IGNORED);
        raw.setHold(null);
        raw.setNote(r.template().isPresent() ? MessageReader.NOT_A_TXN_NOTE : "Not a transaction (you said so).");
        int also = 0;
        if (r.template().isPresent()) {
            for (EmailStatus st : List.of(EmailStatus.NEEDS_REVIEW, EmailStatus.ERROR)) {
                for (InboxMessage other : emails.findByStatus(st)) {
                    if (other.getChannel() != raw.getChannel() || other.getId().equals(raw.getId())) continue;
                    if (smsReader.match(other.getChannel(), other.getBodyText(), other.getReceivedAt()).filter(LearnedFormatReader.Match::skip).isEmpty()) continue;
                    other.setStatus(EmailStatus.IGNORED);
                    other.setHold(null);
                    other.setNote(MessageReader.NOT_A_TXN_NOTE);
                    also++;
                }
            }
        }
        return new SkipResult(raw, r.template().isPresent(), r.conflicts(), also);
    }

    private static final Set<String> FREE_MAIL = Set.of("gmail.com", "googlemail.com", "yahoo.com",
            "yahoo.co.in", "outlook.com", "hotmail.com", "live.com", "icloud.com", "me.com", "mac.com", "proton.me",
            "protonmail.com", "rediffmail.com", "zoho.com", "aol.com");

    /** I've said this email is a real alert: its bank's domain may send them (unless it's webmail or flagged). */
    private void trustTaughtSender(InboxMessage raw) {
        String domain = SenderTrust.domainOf(raw.getFromAddress());
        if (domain == null || FREE_MAIL.contains(domain) || SenderTrust.FAIL.equals(raw.getSenderCheck())) return;
        trust.trust(domain);
    }
}
