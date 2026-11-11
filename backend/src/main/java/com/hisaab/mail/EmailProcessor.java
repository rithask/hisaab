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

    public EmailProcessor(List<BankEmailParser> parsers, RawEmailRepo emails, AccountRepo accounts,
                          StatementFileRepo statements, StatementStorage storage, TransactionService txns,
                          SmsParser smsParser, SmsLearningService learning, com.hisaab.service.BalanceService balances,
                          org.springframework.context.ApplicationEventPublisher events, SenderTrust trust,
                          com.hisaab.service.PayeeAliasService aliases) {
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
        ParsedTransaction p = learning.match(raw.getBodyText(), raw.getReceivedAt()).map(SmsLearningService.Match::txn)
                .orElseGet(() -> smsParser.parse(raw.getSender(), raw.getBodyText(), raw.getReceivedAt()).txn());
        String flat = raw.getBodyText() == null ? "" : raw.getBodyText().replaceAll("\\s+", " ").trim();
        List<Txn> existing = txns.forMessage(raw);
        Direction direction = p == null ? null : p.direction();
        java.math.BigDecimal amount = p == null ? null : p.amount();
        Long accountId = p == null ? null : smsAccount(p).map(Account::getId).orElse(null);
        String name = p == null ? null : p.counterparty();
        if (!existing.isEmpty()) {
            Txn t = existing.get(0);
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
        return new Preview(direction, amount, accountId, name, flat, amountAt, payeeAt);
    }

    /** @param flat the message with whitespace collapsed; {@code amountAt}/{@code payeeAt} are {start, end} in it */
    public record Preview(Direction direction, java.math.BigDecimal amount, Long accountId, String counterparty,
                          String flat, int[] amountAt, int[] payeeAt) {}

    /**
     * @param counterparty the name to show for the payee
     * @param amountAt     where I marked the amount ({start, end} in the collapsed text), if I did
     * @param payeeAt      where I marked the payee, if I did
     */
    public record Teaching(Direction direction, java.math.BigDecimal amount, Long accountId, String counterparty,
                           int[] amountAt, int[] payeeAt) {}

    /**
     * @param alsoFixed other waiting messages that could be recorded thanks to what was just learned
     * @param conflicts when no format was learned because of the safety net: how many earlier lessons it would break
     */
    public record TeachResult(RawEmail message, Txn txn, boolean learnedFormat, boolean learnedAccount, int alsoFixed, int conflicts) {}

    /**
     * I say what an SMS really was. The transaction is recorded (replacing a wrong one if it had been parsed),
     * the wording becomes a learned format, the account link is remembered, and every other message still
     * waiting for review is tried again with the new knowledge.
     */
    @Transactional
    public TeachResult teach(RawEmail raw, Teaching in) {
        if (raw.getChannel() != Channel.SMS) throw new IllegalArgumentException("Only SMS messages can be taught");
        if (in.direction() == null) throw new IllegalArgumentException("Say whether money went out or came in");
        if (in.amount() == null || in.amount().signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
        Account account = accounts.findById(in.accountId() == null ? -1L : in.accountId())
                .orElseThrow(() -> new IllegalArgumentException("Pick the account"));
        String text = raw.getBodyText();
        String flat = text.replaceAll("\\s+", " ").trim();
        // The payee as the bank writes it (what I marked, else what I typed) and the name I want shown for it.
        int[] marked = SmsLearningService.span(in.payeeAt(), flat);
        String name = in.counterparty() == null || in.counterparty().isBlank() ? null : in.counterparty().trim();
        String payee = marked != null ? com.hisaab.mail.parser.ParseUtil.clean(flat.substring(marked[0], marked[1])) : name;
        if (payee != null && name != null && !payee.equals(name)) aliases.remember(payee, name);

        for (Txn old : txns.forMessage(raw)) txns.delete(old.getId());
        txns.unpair(raw);

        SmsLearningService.LearnResult learned = learning.teach(text,
                new SmsLearningService.Truth(in.direction(), in.amount(), account, payee, in.amountAt(), marked), raw);
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
        Optional<Txn> created = txns.createFromEmail(p, account, raw);
        raw.setParser("sms-taught");
        raw.setStatus(created.isPresent() ? EmailStatus.PARSED : EmailStatus.DUPLICATE);
        raw.setNote(created.isPresent() ? null : "Already recorded (from the alert email or an earlier SMS).");
        learning.rememberSender(raw.getSender(), account.getInstitution());

        int alsoFixed = 0;
        for (EmailStatus st : List.of(EmailStatus.NEEDS_REVIEW, EmailStatus.ERROR, EmailStatus.IGNORED)) {
            for (RawEmail other : emails.findByStatus(st)) {
                if (other.getChannel() != Channel.SMS || other.getId().equals(raw.getId())) continue;
                // Messages I chose to ignore are only revisited when a learned format reads them exactly.
                if (st == EmailStatus.IGNORED && learning.match(other.getBodyText(), other.getReceivedAt()).isEmpty()) continue;
                classifySms(other);
                if (other.getStatus() == EmailStatus.PARSED) alsoFixed++;
            }
        }
        return new TeachResult(raw, created.orElse(null), learnedFormat, learnedAccount, alsoFixed, learned.conflicts());
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
