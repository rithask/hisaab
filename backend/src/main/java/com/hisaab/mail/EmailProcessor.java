package com.hisaab.mail;

import com.hisaab.domain.Account;
import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Channel;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.EmailStatus;
import com.hisaab.domain.Enums.Institution;
import com.hisaab.domain.RawEmail;
import com.hisaab.domain.StatementFile;
import com.hisaab.mail.parser.BankEmailParser;
import com.hisaab.mail.parser.ParsedTransaction;
import com.hisaab.mail.parser.SmsParser;
import com.hisaab.domain.Txn;
import com.hisaab.repo.AccountRepo;
import com.hisaab.repo.RawEmailRepo;
import com.hisaab.repo.StatementFileRepo;
import com.hisaab.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides what an email is (transaction alert, statement, noise) and records it.
 * Every email is kept in raw_email so it can be re-processed after parsers or accounts change.
 */
@Service
public class EmailProcessor {
    private static final Logger log = LoggerFactory.getLogger(EmailProcessor.class);

    private static final Pattern MONEY = Pattern.compile("(rs\\.?|inr|₹)\\s*[0-9][0-9,]*(\\.[0-9]{1,2})?|[0-9][0-9,]*(\\.[0-9]{1,2})?\\s*(rs\\.?|inr|₹|rupees)");
    private static final Pattern TXN_WORDS = Pattern.compile("\\b(debited|credited|spent|withdrawn|deposited|received|paid|sent|transferred|moved|purchase|txn|transaction)\\b");
    private static final Pattern NOISE = Pattern.compile("\\b(otp|one time password|verification code)\\b");

    private final List<BankEmailParser> parsers;
    private final RawEmailRepo emails;
    private final AccountRepo accounts;
    private final StatementFileRepo statements;
    private final StatementStorage storage;
    private final TransactionService txns;
    private final SmsParser smsParser;
    private final SmsLearningService learning;
    private final com.hisaab.service.BalanceService balances;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final SenderTrust trust;
    private final com.hisaab.service.PayeeAliasService aliases;
    private final com.hisaab.repo.CategoryRepo categories;

    public EmailProcessor(List<BankEmailParser> parsers, RawEmailRepo emails, AccountRepo accounts,
                          StatementFileRepo statements, StatementStorage storage, TransactionService txns,
                          SmsParser smsParser, SmsLearningService learning, com.hisaab.service.BalanceService balances,
                          org.springframework.context.ApplicationEventPublisher events, SenderTrust trust,
                          com.hisaab.service.PayeeAliasService aliases, com.hisaab.repo.CategoryRepo categories) {
        this.categories = categories;
        this.events = events;
        this.aliases = aliases;
        this.trust = trust;
        this.smsParser = smsParser;
        this.learning = learning;
        this.balances = balances;
        this.parsers = parsers;
        this.emails = emails;
        this.accounts = accounts;
        this.statements = statements;
        this.storage = storage;
        this.txns = txns;
    }

    /** @return false if this message was already seen */
    @Transactional
    public boolean process(IncomingEmail in) {
        if (emails.existsByMessageId(in.messageId())) return false;
        RawEmail raw = new RawEmail();
        raw.setMessageId(in.messageId());
        raw.setFromAddress(trim(in.from(), 300));
        raw.setSubject(trim(in.subject(), 1000));
        raw.setReceivedAt(in.receivedAt());
        raw.setBodyText(in.text());
        raw.setSenderCheck(in.senderCheck());
        raw.setStatus(EmailStatus.NEEDS_REVIEW); // provisional, decided below
        emails.save(raw);

        EmailContent content = new EmailContent(in.from(), in.subject(), in.receivedAt(), in.text());
        List<IncomingEmail.Attachment> pdfs = in.attachments().stream().filter(IncomingEmail.Attachment::isPdf).toList();
        if (!pdfs.isEmpty() && content.haystack().contains("statement")) {
            if (SenderTrust.FAIL.equals(in.senderCheck())) {
                raw.setHold(SenderTrust.UNVERIFIED_SENDER);
                raw.setNote("Your mail provider couldn't confirm this really came from " + SenderTrust.domainOf(in.from())
                        + ", so its statement wasn't saved.");
                return true;
            }
            saveStatements(raw, content, pdfs);
            return true;
        }
        classify(raw, content);
        return true;
    }

    /** Keep a record of an email whose processing blew up, so it shows in the review list instead of vanishing. */
    @Transactional
    public void recordFailure(IncomingEmail in, Exception error) {
        if (emails.existsByMessageId(in.messageId())) return;
        RawEmail raw = new RawEmail();
        raw.setMessageId(in.messageId());
        raw.setFromAddress(trim(in.from(), 300));
        raw.setSubject(trim(in.subject(), 1000));
        raw.setReceivedAt(in.receivedAt());
        raw.setBodyText(in.text());
        raw.setStatus(EmailStatus.ERROR);
        raw.setNote("Processing failed: " + error.getMessage());
        emails.save(raw);
    }

    /** One line saying what was recorded, e.g. "₹500.00 spent · Zomato · HDFC card · Food & Dining". */
    public static String summary(Txn t) {
        java.text.NumberFormat inr = java.text.NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));
        String verb = t.getDirection() == Direction.DEBIT ? (t.isTransfer() ? "moved" : "spent") : "received";
        StringBuilder s = new StringBuilder(inr.format(t.getAmount())).append(' ').append(verb);
        if (t.getCounterparty() != null) s.append(t.getDirection() == Direction.DEBIT ? " · " : " from ").append(t.getCounterparty());
        s.append(" · ").append(t.getAccount().getName());
        if (t.isTransfer()) s.append(" · transfer");
        else if (t.getCategory() != null) s.append(" · ").append(t.getCategory().getName());
        return s.toString();
    }

    /** Outcome of an SMS submission, returned to the caller (e.g. an iPhone Shortcut showing a notification). */
    public record SmsOutcome(RawEmail message, Txn txn, ParsedTransaction parsed, boolean alreadySeen) {}

    /**
     * Records a bank SMS and turns it into a transaction when it is one. The same text submitted twice is
     * recognised and not counted again; a payment already recorded from its alert email is not double counted.
     */
    @Transactional
    public SmsOutcome processSms(String sender, String text, Instant receivedAt) {
        String body = text.strip();
        String id = "sms:" + sha256((sender == null ? "" : sender.strip().toUpperCase(Locale.ROOT)) + "\n" + body.replaceAll("\\s+", " "));
        Optional<RawEmail> seen = emails.findByMessageId(id);
        if (seen.isPresent()) return new SmsOutcome(seen.get(), null, null, true);

        RawEmail raw = new RawEmail();
        raw.setMessageId(id);
        raw.setChannel(Channel.SMS);
        raw.setSender(trim(sender == null ? null : sender.strip(), 100));
        raw.setFromAddress(raw.getSender());
        raw.setSubject("SMS" + (raw.getSender() == null ? "" : " from " + raw.getSender()));
        raw.setReceivedAt(receivedAt == null ? Instant.now() : receivedAt);
        raw.setBodyText(body);
        raw.setStatus(EmailStatus.NEEDS_REVIEW);
        emails.save(raw);
        ClassifyResult r = classifySms(raw);
        return new SmsOutcome(raw, r.txn(), r.parsed(), false);
    }

    private record ClassifyResult(ParsedTransaction parsed, Txn txn) {}

    /**
     * Reads an SMS in order of trust: a format I've taught it first, then the general heuristics. What it has
     * learned about senders and accounts fills the gaps, and the bank's own balance figure is used to catch a
     * wrongly-read direction before anything is recorded.
     */
    private ClassifyResult classifySms(RawEmail raw) {
        raw.setNote(null);
        ParsedTransaction p;
        StringBuilder notes = new StringBuilder();
        try {
            Optional<SmsLearningService.Match> learned = learning.match(raw.getBodyText(), raw.getReceivedAt());
            raw.setTemplate(learned.map(SmsLearningService.Match::template).orElse(null));
            if (learned.isEmpty() && learning.pausedFor(Channel.SMS, raw.getBodyText()).isPresent()) {
                raw.setParser("sms-learned");
                raw.setStatus(EmailStatus.NEEDS_REVIEW);
                raw.setNote(PAUSED_NOTE);
                return new ClassifyResult(null, null);
            }
            if (learned.isPresent() && learned.get().skip()) {
                raw.setParser("sms-learned");
                raw.setStatus(EmailStatus.IGNORED);
                raw.setNote(NOT_A_TXN_NOTE);
                return new ClassifyResult(null, null);
            }
            if (learned.isPresent()) {
                p = learned.get().txn();
                raw.setParser("sms-learned");
            } else {
                raw.setParser("sms");
                SmsParser.Result r = smsParser.parse(raw.getSender(), raw.getBodyText(), raw.getReceivedAt());
                if (r.txn() == null) {
                    String lower = raw.getBodyText().toLowerCase(Locale.ROOT);
                    boolean looksLikeMoney = MONEY.matcher(lower).find() && TXN_WORDS.matcher(lower).find() && !NOISE.matcher(lower).find();
                    raw.setStatus(looksLikeMoney && !r.reason().startsWith("Not a completed") ? EmailStatus.NEEDS_REVIEW : EmailStatus.IGNORED);
                    raw.setNote(r.reason());
                    return new ClassifyResult(null, null);
                }
                p = r.txn();
            }
        } catch (RuntimeException e) {
            log.warn("SMS parser failed on message {}: {}", raw.getId(), e.toString());
            raw.setStatus(EmailStatus.ERROR);
            raw.setNote("Parser error: " + e.getMessage());
            return new ClassifyResult(null, null);
        }

        // A text that doesn't name its bank: use what earlier messages from this sender taught us.
        if (p.institution() == Institution.OTHER) {
            Optional<Institution> known = learning.senderInstitution(raw.getSender());
            if (known.isPresent()) p = p.withInstitution(known.get());
        }

        Optional<Account> account = smsAccount(p);
        if (account.isEmpty()) {
            raw.setStatus(EmailStatus.NEEDS_REVIEW);
            raw.setNote(p.last4() == null
                    ? "Couldn't tell which account this is. Open it and pick the account once; it will be remembered."
                    : "No %s account ending %s. Open it and pick the account once, or add the account.".formatted(p.institution(), p.last4()));
            return new ClassifyResult(p, null);
        }

        boolean balanceAddsUp = balanceDecides(p, account.get());
        ParsedTransaction checked = correctDirectionFromBalance(p, account.get());
        if (checked != p) {
            notes.append("Direction corrected using the balance in the message.");
            p = checked;
        }

        Optional<Txn> created = txns.createFromEmail(p, account.get(), raw);
        raw.setStatus(created.isPresent() ? EmailStatus.PARSED : EmailStatus.DUPLICATE);
        if (created.isEmpty()) notes.append("Already recorded (from the alert email or an earlier SMS).");
        else learning.rememberSender(raw.getSender(), account.get().getInstitution());
        raw.setNote(notes.isEmpty() ? null : notes.toString());
        // Read by the general heuristics and confirmed independently: learn the wording, so the next is read exactly.
        if ("sms".equals(raw.getParser())) {
            boolean emailAgrees = created.isEmpty() && txns.pairedWith(raw).stream().anyMatch(t -> t.getSource() == com.hisaab.domain.Enums.TxnSource.EMAIL);
            if (balanceAddsUp || emailAgrees) {
                learning.autoLearn(Channel.SMS, raw.getBodyText(), new SmsLearningService.Truth(p.direction(), p.amount(), account.get(), p.counterparty()), raw)
                        .template().ifPresent(raw::setTemplate);
            }
        }
        return new ClassifyResult(p, created.orElse(null));
    }

    /** Learned account links first, then the number in the text, then the bank's only account of that kind. */
    private Optional<Account> smsAccount(ParsedTransaction p) {
        Optional<Account> hinted = learning.hintedAccount(p.institution(), p.last4());
        if (hinted.isPresent()) return hinted;
        // The sender id identifies the bank reliably, so a last-4 match at a different bank is not accepted.
        return findAccount(p)
                .filter(a -> p.institution() == Institution.OTHER || a.getInstitution() == Institution.OTHER
                        || a.getInstitution() == p.institution())
                .or(() -> soleAccount(p));
    }

    /**
     * The balance a bank prints after a transaction is arithmetic, not wording, so it outranks the parser: if the
     * figure only makes sense with the opposite direction, the direction is flipped. Left alone when the balance
     * agrees, is absent, or fits neither way (e.g. an earlier transaction is missing).
     */
    /** Whether the balance printed in the message fits exactly one direction (so it confirms the amount and account). */
    private boolean balanceDecides(ParsedTransaction p, Account account) {
        if (p.balance() == null || account.isCard() || !"INR".equals(p.currency())) return false;
        java.math.BigDecimal now = balances.current(account);
        java.math.BigDecimal tol = new java.math.BigDecimal("0.01");
        boolean asDebit = now.subtract(p.amount()).subtract(p.balance()).abs().compareTo(tol) <= 0;
        boolean asCredit = now.add(p.amount()).subtract(p.balance()).abs().compareTo(tol) <= 0;
        return asDebit != asCredit;
    }

    /**
     * This email was matched to a transaction recorded from an SMS: the two agree on amount, direction and account,
     * which confirms the SMS reading. If the general heuristics read that SMS, learn its wording automatically.
     */
    private void confirmSmsTwins(RawEmail email) {
        for (Txn t : txns.pairedWith(email)) {
            RawEmail sms = t.getRawEmail();
            if (t.getSource() != com.hisaab.domain.Enums.TxnSource.SMS || sms == null || !"sms".equals(sms.getParser())) continue;
            SmsParser.Result r = smsParser.parse(sms.getSender(), sms.getBodyText(), sms.getReceivedAt());
            String payee = r.txn() == null ? null : r.txn().counterparty();
            learning.autoLearn(Channel.SMS, sms.getBodyText(), new SmsLearningService.Truth(t.getDirection(), t.getAmount(), t.getAccount(), payee), sms)
                    .template().ifPresent(sms::setTemplate);
        }
    }

    private ParsedTransaction correctDirectionFromBalance(ParsedTransaction p, Account account) {
        if (p.balance() == null || account.isCard() || !"INR".equals(p.currency())) return p;
        java.math.BigDecimal now = balances.current(account);
        java.math.BigDecimal tol = new java.math.BigDecimal("0.01");
        boolean asDebit = now.subtract(p.amount()).subtract(p.balance()).abs().compareTo(tol) <= 0;
        boolean asCredit = now.add(p.amount()).subtract(p.balance()).abs().compareTo(tol) <= 0;
        if (asDebit == asCredit) return p;
        Direction right = asDebit ? Direction.DEBIT : Direction.CREDIT;
        return right == p.direction() ? p : p.withDirection(right);
    }

    // ---- teaching -------------------------------------------------------------------------------------------

    /** What the parser makes of a stored SMS right now, to pre-fill the correction form. */
    @Transactional(readOnly = true)
    public Preview preview(RawEmail raw) {
        ParsedTransaction p = learning.match(raw.getChannel(), raw.getBodyText(), raw.getReceivedAt()).map(SmsLearningService.Match::txn)
                .orElseGet(() -> smsParser.parse(raw.getSender(), raw.getBodyText(), raw.getReceivedAt()).txn());
        String flat = raw.getBodyText() == null ? "" : raw.getBodyText().replaceAll("\\s+", " ").trim();
        List<Txn> existing = txns.forMessage(raw);
        Direction direction = p == null ? null : p.direction();
        java.math.BigDecimal amount = p == null ? null : p.amount();
        Long accountId = p == null ? null : smsAccount(p).map(Account::getId).orElse(null);
        String name = p == null ? null : p.counterparty();
        Long categoryId = null;
        if (!existing.isEmpty()) {
            Txn t = existing.get(0);
            categoryId = t.getCategory() == null || t.isCategorySuggested() ? null : t.getCategory().getId();
            direction = t.getDirection();
            amount = t.getAmount();
            accountId = t.getAccount().getId();
            name = t.getCounterparty();
        }
        // Where those are in the text, so the form can show them marked.
        int[] amountAt = amount == null ? null : SmsLearningService.findAmount(flat, amount);
        int[] payeeAt = null;
        for (String v : java.util.Arrays.asList(p == null ? null : p.counterparty(), name)) {
            int at = v == null || v.isBlank() ? -1 : flat.toLowerCase(Locale.ROOT).indexOf(v.toLowerCase(Locale.ROOT));
            if (at >= 0) {
                payeeAt = new int[]{at, at + v.length()};
                break;
            }
        }
        return new Preview(direction, amount, accountId, name, flat, amountAt, payeeAt, categoryId);
    }

    /**
     * @param flat       the message with whitespace collapsed; {@code amountAt}/{@code payeeAt} are {start, end} in it
     * @param categoryId the category I already chose for its transaction, if any
     */
    public record Preview(Direction direction, java.math.BigDecimal amount, Long accountId, String counterparty,
                          String flat, int[] amountAt, int[] payeeAt, Long categoryId) {}

    /**
     * @param counterparty the name to show for the payee
     * @param amountAt     where I marked the amount ({start, end} in the collapsed text), if I did
     * @param payeeAt      where I marked the payee, if I did
     * @param categoryId   the category it belongs in, if I picked one (learned for its payee, or for its wording
     *                     when it has no payee)
     */
    public record Teaching(Direction direction, java.math.BigDecimal amount, Long accountId, String counterparty,
                           int[] amountAt, int[] payeeAt, Long categoryId) {}

    /**
     * @param alsoFixed other waiting messages that could be recorded thanks to what was just learned
     * @param conflicts when no format was learned because of the safety net: how many earlier lessons it would break
     * @param category  the category I picked, if any
     * @param categoryByWording that category was remembered for messages worded like this (it has no payee)
     */
    public record TeachResult(RawEmail message, Txn txn, boolean learnedFormat, boolean learnedAccount, int alsoFixed, int conflicts,
                              boolean pausedFormat, boolean mergedFormat, String category, boolean categoryByWording) {}

    /**
     * I say what an SMS or bank email really was. The transaction is recorded (replacing a wrong one if it had been
     * parsed), the wording becomes a learned format, the account link is remembered, and every other message still
     * waiting for review is tried again with the new knowledge. Teaching an email also trusts its sender's domain
     * (not a free mail domain, and not one the provider flagged as forged), since I've said its alerts are real.
     */
    @Transactional
    public TeachResult teach(RawEmail raw, Teaching in) {
        if (raw.getStatus() == EmailStatus.STATEMENT) throw new IllegalArgumentException("Statements are imported, not taught");
        if (in.direction() == null) throw new IllegalArgumentException("Say whether money went out or came in");
        if (in.amount() == null || in.amount().signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
        Account account = accounts.findById(in.accountId() == null ? -1L : in.accountId())
                .orElseThrow(() -> new IllegalArgumentException("Pick the account"));
        com.hisaab.domain.Category category = in.categoryId() == null ? null : categories.findById(in.categoryId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown category"));
        if (category != null && category.getKind() != com.hisaab.service.CategorizationService.kindFor(in.direction())) {
            throw new IllegalArgumentException(in.direction() == Direction.DEBIT ? "Pick a spending category for money going out"
                    : "Pick an income category for money coming in");
        }
        String text = raw.getBodyText();
        String flat = text.replaceAll("\\s+", " ").trim();
        // The payee as the bank writes it (what I marked, else what I typed) and the name I want shown for it.
        int[] marked = SmsLearningService.span(in.payeeAt(), flat);
        String name = in.counterparty() == null || in.counterparty().isBlank() ? null : in.counterparty().trim();
        String payee = marked != null ? com.hisaab.mail.parser.ParseUtil.clean(flat.substring(marked[0], marked[1])) : name;
        if (payee != null && name != null && !payee.equals(name)) aliases.remember(payee, name);

        // A transaction already recorded from it is fixed in place, so its category, notes, tags and split stay.
        List<Txn> recorded = txns.forMessage(raw);
        for (Txn extra : recorded.stream().skip(1).toList()) txns.delete(extra.getId());
        if (recorded.isEmpty()) txns.unpair(raw);

        SmsLearningService.Truth truth = new SmsLearningService.Truth(in.direction(), in.amount(), account, payee, in.amountAt(), marked);
        // the format that read it (if one did) was wrong: count that against it before learning the right one
        com.hisaab.domain.SmsTemplate readBy = raw.getTemplate();
        boolean paused = learning.corrected(readBy, text, truth);
        SmsLearningService.LearnResult learned = learning.teach(raw.getChannel(), text, truth, raw);
        // read by the format just learned from now on (it's what a category is remembered for when there's no payee)
        raw.setTemplate(learned.template().orElse(null));
        if (raw.getChannel() == Channel.EMAIL) trustTaughtSender(raw);
        boolean learnedFormat = learned.template().isPresent();

        // If the digits in the text aren't this account's (or there are none), remember what they mean.
        int[] l4 = SmsParser.findLast4(flat);
        String textLast4 = l4 == null ? null : flat.substring(l4[0], l4[1]);
        boolean learnedAccount = false;
        if (textLast4 == null || !textLast4.equals(account.getLast4())) {
            learning.rememberAccount(account.getInstitution(), textLast4, account);
            learnedAccount = true;
        }

        SmsParser.When w = SmsParser.findWhen(flat, raw.getReceivedAt());
        int[] ref = SmsParser.findReference(flat);
        int[] bal = SmsParser.findBalance(flat);
        ParsedTransaction p = new ParsedTransaction(account.getInstitution(),
                account.isCard() ? AccountType.CREDIT_CARD : AccountType.BANK, in.direction(), in.amount().setScale(2, java.math.RoundingMode.HALF_UP),
                "INR", account.getLast4(), w.date(), w.time(), payee, ref == null ? null : flat.substring(ref[0], ref[1]),
                bal == null || account.isCard() ? null : SmsParser.toAmount(flat.substring(bal[0], bal[1])),
                account.isCard() && in.direction() == Direction.CREDIT, null);
        Optional<Txn> created = recorded.isEmpty() ? txns.createFromEmail(p, account, raw)
                : Optional.of(txns.correct(recorded.get(0), p, account));
        boolean sms = raw.getChannel() == Channel.SMS;
        raw.setParser(sms ? "sms-taught" : "email-taught");
        raw.setStatus(created.isPresent() ? EmailStatus.PARSED : EmailStatus.DUPLICATE);
        raw.setNote(created.isPresent() ? null : "Already recorded (from the alert email or an earlier SMS).");
        raw.setHold(null);
        if (sms) learning.rememberSender(raw.getSender(), account.getInstitution());

        // The category I picked, set and learned before the waiting messages are read again, so they get it too.
        // When this one was already recorded from the other channel, it goes on that transaction.
        Optional<Txn> target = created.isPresent() ? created : txns.pairedWith(raw).stream().findFirst();
        boolean byWording = false;
        if (category != null && target.isPresent()) {
            byWording = txns.update(target.get().getId(), new com.hisaab.service.TransactionService.TxnUpdate(
                    category.getId(), null, null, null, null, null, null, null, true)).learnedWording();
        }

        int alsoFixed = 0;
        for (EmailStatus st : List.of(EmailStatus.NEEDS_REVIEW, EmailStatus.ERROR, EmailStatus.IGNORED)) {
            for (RawEmail other : emails.findByStatus(st)) {
                if (other.getChannel() != raw.getChannel() || other.getId().equals(raw.getId())) continue;
                // Messages I chose to ignore are only revisited when a learned format reads them exactly.
                if (st == EmailStatus.IGNORED && learning.match(other.getChannel(), other.getBodyText(), other.getReceivedAt()).filter(x -> !x.skip()).isEmpty()) continue;
                reread(other);
                if (other.getStatus() == EmailStatus.PARSED) alsoFixed++;
            }
        }
        return new TeachResult(raw, created.orElse(null), learnedFormat, learnedAccount, alsoFixed, learned.conflicts(),
                paused && readBy.isPaused(), learned.merged(), category != null && target.isPresent() ? category.getName() : null, byWording);
    }

    static final String NOT_A_TXN_NOTE = "Not a transaction: worded like a message you marked as not one.";
    static final String PAUSED_NOTE = "Worded like a learned format that was paused because it kept being corrected. Check it and teach it.";

    /** @param alsoSkipped other messages waiting for review that are worded the same and were skipped too */
    public record SkipResult(RawEmail message, boolean learned, int conflicts, int alsoSkipped) {}

    /**
     * I say a message isn't a transaction: it's kept as Ignored, any transaction recorded from it is removed, its
     * wording is learned so later ones are skipped, and waiting messages worded the same are skipped now.
     */
    @Transactional
    public SkipResult notATransaction(RawEmail raw) {
        if (raw.getStatus() == EmailStatus.STATEMENT) throw new IllegalArgumentException("Statements are imported, not taught");
        learning.corrected(raw.getTemplate(), raw.getBodyText(), null);
        raw.setTemplate(null);
        SmsLearningService.LearnResult r = learning.skip(raw.getChannel(), raw.getBodyText(), raw);
        for (Txn t : txns.forMessage(raw)) txns.delete(t.getId());
        txns.unpair(raw);
        raw.setStatus(EmailStatus.IGNORED);
        raw.setHold(null);
        raw.setNote(r.template().isPresent() ? NOT_A_TXN_NOTE : "Not a transaction (you said so).");
        int also = 0;
        if (r.template().isPresent()) {
            for (EmailStatus st : List.of(EmailStatus.NEEDS_REVIEW, EmailStatus.ERROR)) {
                for (RawEmail other : emails.findByStatus(st)) {
                    if (other.getChannel() != raw.getChannel() || other.getId().equals(raw.getId())) continue;
                    if (learning.match(other.getChannel(), other.getBodyText(), other.getReceivedAt()).filter(SmsLearningService.Match::skip).isEmpty()) continue;
                    other.setStatus(EmailStatus.IGNORED);
                    other.setHold(null);
                    other.setNote(NOT_A_TXN_NOTE);
                    also++;
                }
            }
        }
        return new SkipResult(raw, r.template().isPresent(), r.conflicts(), also);
    }

    /** Reads a stored message again, whichever channel it came from. */
    private void reread(RawEmail m) {
        if (m.getChannel() == Channel.SMS) classifySms(m);
        else classify(m, new EmailContent(m.getFromAddress(), m.getSubject(), m.getReceivedAt(), m.getBodyText()));
    }

    private static final java.util.Set<String> FREE_MAIL = java.util.Set.of("gmail.com", "googlemail.com", "yahoo.com",
            "yahoo.co.in", "outlook.com", "hotmail.com", "live.com", "icloud.com", "me.com", "mac.com", "proton.me",
            "protonmail.com", "rediffmail.com", "zoho.com", "aol.com");

    /** I've said this email is a real alert: its bank's domain may send them (unless it's webmail or flagged). */
    private void trustTaughtSender(RawEmail raw) {
        String domain = SenderTrust.domainOf(raw.getFromAddress());
        if (domain == null || FREE_MAIL.contains(domain) || SenderTrust.FAIL.equals(raw.getSenderCheck())) return;
        trust.trust(domain);
    }

    /** SMS without an account number: use the bank's only account of that kind, if there is exactly one. */
    private Optional<Account> soleAccount(ParsedTransaction p) {
        if (p.last4() != null || p.institution() == Institution.OTHER) return Optional.empty();
        List<Account> m = accounts.findByArchivedFalseOrderByTypeAscNameAsc().stream()
                .filter(a -> a.getInstitution() == p.institution())
                .filter(a -> (a.getType() == AccountType.CREDIT_CARD) == (p.accountType() == AccountType.CREDIT_CARD))
                .toList();
        return m.size() == 1 ? Optional.of(m.get(0)) : Optional.empty();
    }

    private static String sha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Re-run parsing on a stored email (e.g. after adding the missing account). */
    @Transactional
    public RawEmail reprocess(RawEmail raw) {
        if (raw.getStatus() == EmailStatus.PARSED || raw.getStatus() == EmailStatus.STATEMENT) return raw;
        if (raw.getChannel() == Channel.SMS) {
            classifySms(raw);
            return raw;
        }
        classify(raw, new EmailContent(raw.getFromAddress(), raw.getSubject(), raw.getReceivedAt(), raw.getBodyText()));
        return raw;
    }

    private void classify(RawEmail raw, EmailContent content) {
        raw.setNote(null);
        raw.setHold(null);
        // A format I taught for emails comes first: where I've corrected a reading, my answer wins.
        Optional<SmsLearningService.Match> learned = learning.match(Channel.EMAIL, raw.getBodyText(), raw.getReceivedAt());
        raw.setTemplate(learned.map(SmsLearningService.Match::template).orElse(null));
        if (learned.isEmpty() && learning.pausedFor(Channel.EMAIL, raw.getBodyText()).isPresent()) {
            raw.setParser("email-learned");
            raw.setStatus(EmailStatus.NEEDS_REVIEW);
            raw.setNote(PAUSED_NOTE);
            return;
        }
        if (learned.isPresent() && learned.get().skip()) {
            raw.setParser("email-learned");
            raw.setStatus(EmailStatus.IGNORED);
            raw.setNote(NOT_A_TXN_NOTE);
            return;
        }
        if (learned.isPresent()) {
            ParsedTransaction p = learned.get().txn();
            raw.setParser("email-learned");
            SenderTrust.Verdict v = trust.judge(raw.getFromAddress(), raw.getSenderCheck(), p.institution(), bankName(p.institution()));
            if (!v.ok()) {
                raw.setStatus(EmailStatus.NEEDS_REVIEW);
                raw.setHold(v.hold());
                raw.setNote(v.note());
                return;
            }
            Optional<Account> account = smsAccount(p);
            if (account.isEmpty()) {
                raw.setStatus(EmailStatus.NEEDS_REVIEW);
                raw.setNote("Read with a format you taught, but there's no matching account. Open it and pick the account.");
                return;
            }
            raw.setStatus(txns.createFromEmail(p, account.get(), raw).isPresent() ? EmailStatus.PARSED : EmailStatus.DUPLICATE);
            if (raw.getStatus() == EmailStatus.DUPLICATE) confirmSmsTwins(raw);
            return;
        }
        for (BankEmailParser parser : parsers) {
            Optional<ParsedTransaction> parsed;
            try {
                parsed = parser.parse(content);
            } catch (RuntimeException e) {
                log.warn("Parser {} failed on email {}: {}", parser.name(), raw.getId(), e.toString());
                raw.setStatus(EmailStatus.ERROR);
                raw.setParser(parser.name());
                raw.setNote("Parser error: " + e.getMessage());
                return;
            }
            if (parsed.isEmpty()) continue;
            ParsedTransaction p = parsed.get();
            raw.setParser(parser.name());
            SenderTrust.Verdict v = trust.judge(raw.getFromAddress(), raw.getSenderCheck(), p.institution(), bankName(p.institution()));
            if (!v.ok()) {
                raw.setStatus(EmailStatus.NEEDS_REVIEW);
                raw.setHold(v.hold());
                raw.setNote(v.note());
                return;
            }
            Optional<Account> account = findAccount(p);
            if (account.isEmpty()) {
                raw.setStatus(EmailStatus.NEEDS_REVIEW);
                raw.setNote("No %s account ending %s. Add it under Accounts, then reprocess."
                        .formatted(p.institution(), p.last4()));
                return;
            }
            boolean created = txns.createFromEmail(p, account.get(), raw).isPresent();
            raw.setStatus(created ? EmailStatus.PARSED : EmailStatus.DUPLICATE);
            if (!created) confirmSmsTwins(raw);
            return;
        }
        String hay = content.haystack();
        if (!NOISE.matcher(hay).find() && MONEY.matcher(hay).find() && TXN_WORDS.matcher(hay).find()) {
            raw.setStatus(EmailStatus.NEEDS_REVIEW);
            raw.setNote("Looks like a transaction but no parser understood it.");
        } else {
            raw.setStatus(EmailStatus.IGNORED);
        }
    }

    private static String bankName(Institution i) {
        return switch (i) {
            case HDFC -> "HDFC Bank";
            case AXIS -> "Axis Bank";
            case SBI -> "SBI";
            case CANARA -> "Canara Bank";
            case OTHER -> "bank";
        };
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
        for (RawEmail e : emails.findByStatus(EmailStatus.NEEDS_REVIEW)) {
            if (!SenderTrust.UNKNOWN_SENDER.equals(e.getHold())) continue;
            String d = SenderTrust.domainOf(e.getFromAddress());
            if (d == null || !(d.equals(domain) || d.endsWith("." + domain))) continue;
            reprocess(e);
            if (e.getStatus() == EmailStatus.PARSED || e.getStatus() == EmailStatus.DUPLICATE) n++;
        }
        return n;
    }

    private Optional<Account> findAccount(ParsedTransaction p) {
        if (p.last4() == null) return Optional.empty();
        List<Account> matches = accounts.findByLast4AndArchivedFalse(p.last4());
        if (matches.size() <= 1) return matches.stream().findFirst();
        return matches.stream().filter(a -> a.getInstitution() == p.institution() && a.getType() == p.accountType())
                .findFirst().or(() -> matches.stream().filter(a -> a.getInstitution() == p.institution()).findFirst());
    }

    private void saveStatements(RawEmail raw, EmailContent content, List<IncomingEmail.Attachment> pdfs) {
        Account account = guessStatementAccount(content);
        StringBuilder note = new StringBuilder();
        for (IncomingEmail.Attachment a : pdfs) {
            try {
                Path path = storage.save(account, raw.getReceivedAt(), a.fileName(), a.data());
                StatementFile f = new StatementFile();
                f.setAccount(account);
                f.setRawEmail(raw);
                f.setFileName(a.fileName() == null ? path.getFileName().toString() : a.fileName());
                f.setStoredPath(storage.toStored(path));
                f.setSizeBytes(a.data().length);
                f.setReceivedAt(raw.getReceivedAt());
                f.setPeriodLabel(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
                        .withZone(com.hisaab.service.Money.ZONE).format(raw.getReceivedAt()));
                statements.save(f);
                events.publishEvent(new com.hisaab.statement.StatementSaved(f.getId()));
            } catch (Exception e) {
                log.warn("Couldn't save statement {} from email {}: {}", a.fileName(), raw.getId(), e.toString());
                note.append("Failed to save ").append(a.fileName()).append(": ").append(e.getMessage()).append('\n');
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
