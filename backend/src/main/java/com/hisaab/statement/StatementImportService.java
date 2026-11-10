package com.hisaab.statement;

import com.hisaab.domain.Account;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.ImportStatus;
import com.hisaab.domain.Enums.LineDecision;
import com.hisaab.domain.Enums.LineKind;
import com.hisaab.domain.Enums.TxnSource;
import com.hisaab.domain.StatementFile;
import com.hisaab.domain.StatementImport;
import com.hisaab.domain.StatementImportLine;
import com.hisaab.domain.Txn;
import com.hisaab.mail.StatementStorage;
import com.hisaab.repo.AccountRepo;
import com.hisaab.repo.StatementFileRepo;
import com.hisaab.repo.StatementImportLineRepo;
import com.hisaab.repo.StatementImportRepo;
import com.hisaab.repo.TxnRepo;
import com.hisaab.service.BalanceService;
import com.hisaab.service.Money;
import com.hisaab.service.Names;
import com.hisaab.service.SettingsService;
import com.hisaab.service.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Reads a statement, lines it up against what's already recorded, and imports only what I approve.
 * <p>For every statement line the review says one of three things:
 * <ul>
 *   <li><b>MATCH</b>: already recorded. Importing fills in what the alert didn't have: the real time, a reference
 *       number, the payee, or the exact rupee amount of a foreign-currency spend.</li>
 *   <li><b>POSSIBLE</b>: something similar is recorded (same amount, but days apart or a different name). I decide
 *       whether it's the same transaction.</li>
 *   <li><b>NEW</b>: not recorded; importing adds it.</li>
 * </ul>
 * Nothing changes until I confirm, and every possible match needs an explicit answer first.
 */
@Service
public class StatementImportService {
    private static final String PASSWORD_PREFIX = "statement.password.";
    private static final Duration WINDOW = Duration.ofDays(25);

    private final List<StatementParser> parsers;
    private final StatementImportRepo imports;
    private final StatementImportLineRepo lines;
    private final AccountRepo accounts;
    private final TxnRepo txns;
    private final TransactionService transactions;
    private final BalanceService balances;
    private final StatementStorage storage;
    private final StatementFileRepo statementFiles;
    private final SettingsService settings;
    private final com.hisaab.repo.AppSettingRepo settingRepo;
    private final org.springframework.context.ApplicationEventPublisher events;

    public StatementImportService(List<StatementParser> parsers, StatementImportRepo imports, StatementImportLineRepo lines,
                                  AccountRepo accounts, TxnRepo txns, TransactionService transactions, BalanceService balances,
                                  StatementStorage storage, StatementFileRepo statementFiles, SettingsService settings,
                                  com.hisaab.repo.AppSettingRepo settingRepo,
                                  org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.parsers = parsers;
        this.imports = imports;
        this.lines = lines;
        this.accounts = accounts;
        this.txns = txns;
        this.transactions = transactions;
        this.balances = balances;
        this.storage = storage;
        this.statementFiles = statementFiles;
        this.settings = settings;
        this.settingRepo = settingRepo;
    }

    // ---- reading ------------------------------------------------------------------------------------------

    /** Thrown when the PDF opened but isn't a statement format I can read. */
    public static class UnsupportedStatement extends IllegalArgumentException {
        public UnsupportedStatement(String m) {
            super(m);
        }
    }

    /**
     * Reads an uploaded statement (or one saved from email) and prepares it for review.
     *
     * @param password tried along with any remembered statement passwords; may be null
     */
    @Transactional(noRollbackFor = UnsupportedStatement.class)
    public StatementImport start(byte[] pdf, String fileName, String password, boolean remember, StatementFile fromEmail)
            throws IOException {
        List<String> candidates = new ArrayList<>();
        if (password != null) candidates.add(password);
        settingRepo.findAll().stream().filter(s -> s.getKey().startsWith(PASSWORD_PREFIX) && s.getValue() != null)
                .forEach(s -> candidates.add(s.getValue()));
        PdfText.Opened opened = PdfText.open(pdf, candidates);

        StatementParser parser = parsers.stream().filter(p -> p.supports(opened.text())).findFirst()
                .orElseThrow(() -> new UnsupportedStatement("This statement's format isn't supported yet. Supported: Canara Bank, Axis Bank account, Axis credit card and HDFC credit card statements."));
        ParsedStatement s = parser.parse(opened.text());
        if (s.rows().isEmpty()) throw new UnsupportedStatement("No transactions were found in this statement.");

        StatementImport imp = new StatementImport();
        imp.setParser(s.parser());
        imp.setLabel(s.label());
        imp.setInstitution(s.institution());
        imp.setAccountType(s.accountType());
        imp.setLast4(s.last4());
        imp.setPeriodFrom(s.from());
        imp.setPeriodTo(s.to());
        imp.setOpeningBalance(s.opening());
        imp.setClosingBalance(s.closing());
        imp.setCreditLimit(s.creditLimit());
        imp.setChecks(encodeChecks(s.checks()));
        imp.setFileName(fileName);
        imp.setStatus(ImportStatus.REVIEW);
        imp.setStatementFile(fromEmail);
        if (remember && !opened.password().isEmpty()) imp.setRememberPassword(opened.password());
        if (fromEmail == null) imp.setStoredPath(storage.toStored(storage.save(null, Instant.now(), fileName, pdf)));
        // The number printed on the statement beats the guess made from the email's wording.
        imp.setAccount(findAccount(s).orElse(fromEmail != null ? fromEmail.getAccount() : null));
        imports.save(imp);

        int n = 0;
        for (StatementRow r : s.rows()) {
            StatementImportLine l = new StatementImportLine();
            l.setStatementImport(imp);
            l.setLineNo(n++);
            l.setTxnDate(r.date());
            l.setTxnTime(r.time());
            l.setDescription(r.description());
            l.setCounterparty(r.counterparty());
            l.setReferenceNo(r.reference());
            l.setDirection(r.direction());
            l.setAmount(r.amount());
            l.setBalance(r.balance());
            l.setCardPayment(r.cardPayment());
            l.setUncertain(r.uncertain());
            lines.save(l);
        }
        if (imp.getAccount() != null) reconcile(imp);
        if (fromEmail != null) {
            fromEmail.setImportStatus("REVIEW");
            fromEmail.setImportNote(null);
        }
        return imp;
    }

    /** An existing review for this saved statement, so opening it twice doesn't create a second one. */
    public Optional<StatementImport> openReviewFor(StatementFile f) {
        return imports.findFirstByStatementFileAndStatus(f, ImportStatus.REVIEW);
    }

    public long pendingCount() {
        return imports.countByStatus(ImportStatus.REVIEW);
    }

    private Optional<Account> findAccount(ParsedStatement s) {
        if (s.last4() == null) return Optional.empty();
        return accounts.findByLast4AndArchivedFalse(s.last4()).stream()
                .filter(a -> a.getInstitution() == s.institution() && a.isCard() == (s.accountType() == com.hisaab.domain.Enums.AccountType.CREDIT_CARD))
                .findFirst();
    }

    @Transactional
    public StatementImport setAccount(Long importId, Long accountId) {
        StatementImport imp = review(importId);
        imp.setAccount(accounts.findById(accountId).orElseThrow(() -> new IllegalArgumentException("Unknown account")));
        reconcile(imp);
        return imp;
    }

    // ---- matching -----------------------------------------------------------------------------------------

    private record Pair(StatementImportLine line, Txn txn, double score, boolean exact, String reason) {}

    /** Lines every statement row up with at most one recorded transaction, best evidence first. */
    void reconcile(StatementImport imp) {
        Account account = imp.getAccount();
        List<StatementImportLine> ls = lines.findByStatementImportOrderByLineNo(imp);
        LocalDate from = ls.stream().map(StatementImportLine::getTxnDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to = ls.stream().map(StatementImportLine::getTxnDate).max(Comparator.naturalOrder()).orElseThrow();
        List<Txn> existing = txns.findForReconcile(account, startOf(from).minus(WINDOW), startOf(to.plusDays(1)).plus(WINDOW), TxnSource.ADJUSTMENT);

        // Answers I gave on an earlier import of the same statement are remembered: a transaction already
        // linked to an identical line matches it outright.
        Map<Long, List<StatementImportLine>> earlier = new HashMap<>();
        if (!existing.isEmpty()) {
            for (StatementImportLine e : lines.findImportedFor(existing)) {
                earlier.computeIfAbsent(e.getCreatedTxn().getId(), k -> new ArrayList<>()).add(e);
            }
        }

        List<Pair> pairs = new ArrayList<>();
        for (StatementImportLine l : ls) {
            for (Txn t : existing) {
                boolean seenBefore = earlier.getOrDefault(t.getId(), List.of()).stream().anyMatch(e -> sameLine(e, l));
                if (seenBefore) pairs.add(new Pair(l, t, 2000, true, "Matched when this statement was imported before"));
                else score(l, t).ifPresent(pairs::add);
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::score).reversed());

        Set<Long> usedTxns = new HashSet<>();
        Map<Long, Pair> chosen = new HashMap<>();
        for (Pair p : pairs) {
            if (chosen.containsKey(p.line().getId()) || usedTxns.contains(p.txn().getId())) continue;
            chosen.put(p.line().getId(), p);
            usedTxns.add(p.txn().getId());
        }
        for (StatementImportLine l : ls) {
            Pair p = chosen.get(l.getId());
            if (p == null) {
                l.setKind(LineKind.NEW);
                l.setMatchTxn(null);
                l.setMatchReason(null);
                l.setDecision(l.getUncertain() == null ? LineDecision.ADD : null);
            } else {
                l.setKind(p.exact() ? LineKind.MATCH : LineKind.POSSIBLE);
                l.setMatchTxn(p.txn());
                l.setMatchReason(p.reason());
                l.setDecision(p.exact() ? LineDecision.MERGE : null);
            }
        }
    }

    private static boolean sameLine(StatementImportLine a, StatementImportLine b) {
        return a.getTxnDate().equals(b.getTxnDate()) && a.getDirection() == b.getDirection() && a.getAmount().compareTo(b.getAmount()) == 0
                && Objects.equals(a.getTxnTime(), b.getTxnTime()) && Objects.equals(a.getDescription(), b.getDescription());
    }

    /** How strongly a statement line and a recorded transaction look like the same thing, if at all. */
    private Optional<Pair> score(StatementImportLine l, Txn t) {
        if (t.getDirection() != l.getDirection()) return Optional.empty();
        boolean sameAmount = t.getAmount().compareTo(l.getAmount()) == 0;
        // A foreign-currency spend is recorded at an estimated rate; the statement has the real rupee amount.
        boolean estimated = t.isAmountEstimated() && l.getAmount().subtract(t.getAmount()).abs()
                .compareTo(t.getAmount().multiply(new BigDecimal("0.15"))) <= 0;
        if (!sameAmount && !estimated) return Optional.empty();

        boolean sameRef = l.getReferenceNo() != null && l.getReferenceNo().equals(t.getReferenceNo());
        LocalDateTime when = t.getOccurredAt().atZone(Money.INDIA).toLocalDateTime();
        long days = Math.abs(ChronoUnit.DAYS.between(when.toLocalDate(), l.getTxnDate()));
        long minutes = l.getTxnTime() != null && !t.isTimeEstimated()
                ? Math.abs(Duration.between(when, l.getTxnDate().atTime(l.getTxnTime())).toMinutes()) : -1;
        boolean close = minutes >= 0 ? minutes <= 90 : days <= 1;
        boolean similarName = similar(l.getCounterparty(), t.getCounterparty());
        boolean nameless = t.getCounterparty() == null || l.getCounterparty() == null;

        String reason;
        boolean exact;
        if (sameRef && sameAmount) {
            exact = true;
            reason = "Same reference number";
        } else if (sameAmount && close && (similarName || nameless || days == 0)) {
            exact = true;
            reason = minutes >= 0 ? "Same amount and time" : "Same amount and date";
        } else if (estimated && days <= 3 && (similarName || nameless)) {
            exact = false;
            reason = "Foreign-currency spend; the statement has the exact rupee amount";
        } else if (sameAmount && (close || days <= 3)) {
            exact = false;
            reason = "Same amount " + (days == 0 ? "on the same day" : days + " day" + (days > 1 ? "s" : "") + " apart") + ", but the payee looks different";
        } else if (sameAmount && days <= 20 && similarName) {
            exact = false;
            reason = "Same amount and payee, but " + days + " days apart";
        } else {
            return Optional.empty();
        }
        double score = (sameRef ? 1000 : 0) + (exact ? 500 : 0) + (similarName ? 100 : 0) + (sameAmount ? 50 : 0)
                - days * 10 - (minutes >= 0 ? minutes / 10.0 : 0);
        return Optional.of(new Pair(l, t, score, exact, reason));
    }

    static boolean similar(String a, String b) {
        String ka = Names.merchantKey(a), kb = Names.merchantKey(b);
        if (ka == null || kb == null) return false;
        Set<String> ta = new HashSet<>(Arrays.asList(ka.split(" "))), tb = new HashSet<>(Arrays.asList(kb.split(" ")));
        ta.removeIf(x -> x.length() < 3);
        tb.removeIf(x -> x.length() < 3);
        ta.retainAll(tb);
        return !ta.isEmpty() || ka.replace(" ", "").contains(kb.replace(" ", "")) || kb.replace(" ", "").contains(ka.replace(" ", ""));
    }

    // ---- what a match would fill in -----------------------------------------------------------------------

    public record Fill(String field, String label, String from, String to) {}

    /** Details the statement has that the recorded transaction is missing. */
    public List<Fill> fills(StatementImportLine l, Txn t) {
        List<Fill> out = new ArrayList<>();
        if (isBlank(t.getCounterparty()) && !isBlank(l.getCounterparty())) out.add(new Fill("counterparty", "Payee", null, l.getCounterparty()));
        if (isBlank(t.getReferenceNo()) && !isBlank(l.getReferenceNo())) out.add(new Fill("reference", "Reference", null, l.getReferenceNo()));
        if (t.isTimeEstimated() && l.getTxnTime() != null) {
            out.add(new Fill("time", "Time", null, l.getTxnTime().format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).toLowerCase()));
        }
        if (t.isAmountEstimated() && l.getAmount().compareTo(t.getAmount()) != 0) {
            out.add(new Fill("amount", "Amount", t.getAmount().toPlainString(), l.getAmount().toPlainString()));
        } else if (t.isAmountEstimated()) {
            out.add(new Fill("amount", "Amount", null, "confirmed from statement"));
        }
        if (isBlank(t.getDescription()) && !isBlank(l.getDescription()) && !l.getDescription().equals(l.getCounterparty())) {
            out.add(new Fill("description", "Bank's description", null, l.getDescription()));
        }
        return out;
    }

    private void applyFills(StatementImportLine l, Txn t) {
        for (Fill f : fills(l, t)) {
            switch (f.field()) {
                case "counterparty" -> {
                    t.setCounterparty(l.getCounterparty());
                    t.setMerchantKey(Names.merchantKey(l.getCounterparty()));
                }
                case "reference" -> t.setReferenceNo(l.getReferenceNo());
                case "time" -> {
                    t.setOccurredAt(l.getTxnDate().atTime(l.getTxnTime()).atZone(Money.INDIA).toInstant());
                    t.setTimeEstimated(false);
                }
                case "amount" -> {
                    t.setAmount(Money.r2(l.getAmount()));
                    t.setAmountEstimated(false);
                }
                case "description" -> t.setDescription(l.getDescription().length() <= 500 ? l.getDescription() : l.getDescription().substring(0, 500));
                default -> { }
            }
        }
    }

    // ---- balance effect -----------------------------------------------------------------------------------

    /** Instant a statement line happened at (noon when only the date is known). */
    public static Instant when(StatementImportLine l) {
        return (l.getTxnTime() == null ? l.getTxnDate().atTime(12, 0) : l.getTxnDate().atTime(l.getTxnTime())).atZone(Money.INDIA).toInstant();
    }

    /** Lines dated before the account's starting balance are history only; they never change the balance. */
    public boolean beforeStart(StatementImportLine l, Account a) {
        return when(l).isBefore(a.getOpeningDate());
    }

    /** Change to the account's balance (or card outstanding) if this line were added. */
    public BigDecimal balanceEffect(StatementImportLine l, Account a) {
        BigDecimal signed = l.getDirection() == Direction.CREDIT ? l.getAmount() : l.getAmount().negate();
        return a.isCard() ? signed.negate() : signed;
    }

    /**
     * The statement's closing figure next to what the app shows for the same moment, so I can see whether the
     * import will leave the account agreeing with the bank. Null when the statement ends before my starting
     * balance (then it can't change anything).
     */
    public record BalanceCheck(LocalDate at, BigDecimal statement, BigDecimal appNow) {}

    public Optional<BalanceCheck> balanceCheck(StatementImport imp) {
        Account a = imp.getAccount();
        if (a == null || imp.getClosingBalance() == null) return Optional.empty();
        List<StatementImportLine> ls = lines.findByStatementImportOrderByLineNo(imp);
        LocalDate end = imp.getPeriodTo() != null ? imp.getPeriodTo()
                : ls.stream().map(StatementImportLine::getTxnDate).max(Comparator.naturalOrder()).orElseThrow();
        Instant cutoff = startOf(end.plusDays(1));
        if (!cutoff.isAfter(a.getOpeningDate())) return Optional.empty();
        BigDecimal net = txns.netUpTo(a, a.getOpeningDate(), cutoff, Long.MAX_VALUE);
        BigDecimal app = a.isCard() ? a.getOpeningBalance().subtract(net) : a.getOpeningBalance().add(net);
        return Optional.of(new BalanceCheck(end, imp.getClosingBalance(), Money.r2(app)));
    }

    /** Recorded transactions inside the statement's dates that no statement line matched. */
    public List<Txn> notInStatement(StatementImport imp) {
        Account a = imp.getAccount();
        if (a == null) return List.of();
        List<StatementImportLine> ls = lines.findByStatementImportOrderByLineNo(imp);
        LocalDate from = imp.getPeriodFrom() != null ? imp.getPeriodFrom() : ls.getFirst().getTxnDate();
        LocalDate to = imp.getPeriodTo() != null ? imp.getPeriodTo() : ls.getLast().getTxnDate();
        Set<Long> matched = new HashSet<>();
        ls.forEach(l -> {
            if (l.getMatchTxn() != null) matched.add(l.getMatchTxn().getId());
        });
        return txns.findForReconcile(a, startOf(from), startOf(to.plusDays(1)).minusMillis(1), TxnSource.ADJUSTMENT).stream()
                .filter(t -> !matched.contains(t.getId())).toList();
    }

    // ---- decisions & commit -------------------------------------------------------------------------------

    @Transactional
    public StatementImportLine decide(Long importId, Long lineId, LineDecision d) {
        StatementImport imp = review(importId);
        StatementImportLine l = lines.findById(lineId).filter(x -> x.getStatementImport().getId().equals(imp.getId()))
                .orElseThrow(() -> new NoSuchElementException("Line not found"));
        if (d == LineDecision.MERGE && l.getMatchTxn() == null) throw new IllegalArgumentException("Nothing to merge this line with");
        l.setDecision(d);
        return l;
    }

    /** @param startingBalance the account's new starting balance, when I asked to correct it; otherwise null */
    public record Result(int added, int updated, int skipped, BigDecimal startingBalance) {}

    /**
     * Applies my decisions. With {@code fixStart}, the account's starting balance is then corrected so the app
     * agrees with the statement's closing figure (for when the starting balance I entered at setup was off).
     */
    @Transactional
    public Result commit(Long importId, boolean fixStart) {
        StatementImport imp = review(importId);
        Account account = imp.getAccount();
        if (account == null) throw new IllegalArgumentException("Choose which account this statement belongs to first");
        List<StatementImportLine> ls = lines.findByStatementImportOrderByLineNo(imp);
        long open = ls.stream().filter(l -> l.getDecision() == null).count();
        if (open > 0) throw new IllegalArgumentException(open + " line" + (open > 1 ? "s need" : " needs") + " your decision before importing");

        int added = 0, updated = 0, skipped = 0;
        for (StatementImportLine l : ls) {
            switch (l.getDecision()) {
                case MERGE -> {
                    applyFills(l, l.getMatchTxn());
                    l.setCreatedTxn(l.getMatchTxn());
                    updated++;
                }
                case ADD -> {
                    Txn t = transactions.createFromStatement(account, l.getDirection(), l.getAmount(), l.getTxnDate(), l.getTxnTime(),
                            l.getCounterparty(), l.getReferenceNo(), l.getDescription(), l.isCardPayment());
                    l.setCreatedTxn(t);
                    added++;
                }
                case SKIP -> skipped++;
            }
        }

        // Keep the PDF with the account's statements.
        if (imp.getStatementFile() == null && imp.getStoredPath() != null) {
            StatementFile f = new StatementFile();
            f.setAccount(account);
            f.setFileName(imp.getFileName() == null ? "statement.pdf" : imp.getFileName());
            f.setStoredPath(imp.getStoredPath());
            try {
                f.setSizeBytes(Files.size(storage.resolve(imp.getStoredPath())));
            } catch (IOException e) {
                f.setSizeBytes(0);
            }
            f.setReceivedAt(Instant.now());
            LocalDate p = imp.getPeriodTo() != null ? imp.getPeriodTo() : ls.getLast().getTxnDate();
            f.setPeriodLabel(p.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)));
            imp.setStatementFile(statementFiles.save(f));
        } else if (imp.getStatementFile() != null && imp.getStatementFile().getAccount() == null) {
            imp.getStatementFile().setAccount(account);
        }
        BigDecimal newStart = null;
        if (fixStart) {
            txns.flush();
            Optional<BalanceCheck> check = balanceCheck(imp);
            if (check.isPresent()) {
                BigDecimal diff = check.get().statement().subtract(check.get().appNow());
                if (diff.signum() != 0) {
                    newStart = Money.r2(account.getOpeningBalance().add(diff));
                    account.setOpeningBalance(newStart);
                }
            }
        }
        if (imp.getRememberPassword() != null) {
            settings.set(PASSWORD_PREFIX + account.getId(), imp.getRememberPassword());
            // Other emailed statements that were waiting for a password may open with this one.
            statementFiles.findAll().stream().filter(f -> "NEEDS_PASSWORD".equals(f.getImportStatus()))
                    .forEach(f -> events.publishEvent(new StatementSaved(f.getId())));
        }
        imp.setRememberPassword(null);
        if (imp.getStatementFile() != null) imp.getStatementFile().setImportStatus("IMPORTED");
        imp.setStatus(ImportStatus.IMPORTED);
        imp.setCompletedAt(Instant.now());
        return new Result(added, updated, skipped, newStart);
    }

    @Transactional
    public void cancel(Long importId) {
        StatementImport imp = review(importId);
        imp.setStatus(ImportStatus.CANCELLED);
        imp.setRememberPassword(null);
        if (imp.getStatementFile() != null) imp.getStatementFile().setImportStatus("CANCELLED");
        if (imp.getStatementFile() == null && imp.getStoredPath() != null) {
            try {
                Files.deleteIfExists(storage.resolve(imp.getStoredPath()));
            } catch (IOException ignored) {
                // a leftover upload is harmless
            }
            imp.setStoredPath(null);
        }
    }

    public StatementImport get(Long id) {
        return imports.findById(id).orElseThrow(() -> new NoSuchElementException("Import not found"));
    }

    public List<StatementImportLine> lines(StatementImport imp) {
        return lines.findByStatementImportOrderByLineNo(imp);
    }

    public List<StatementImport> pending() {
        return imports.findByStatusOrderByCreatedAtDesc(ImportStatus.REVIEW);
    }

    private StatementImport review(Long id) {
        StatementImport imp = get(id);
        if (imp.getStatus() != ImportStatus.REVIEW) throw new IllegalStateException("This import is already " + imp.getStatus().name().toLowerCase());
        return imp;
    }

    public static List<ParsedStatement.Check> decodeChecks(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split("\n")).map(l -> l.split("\\|", 3)).filter(p -> p.length == 3)
                .map(p -> new ParsedStatement.Check(p[1], p[0].equals("OK"), p[2])).toList();
    }

    private static String encodeChecks(List<ParsedStatement.Check> checks) {
        StringBuilder b = new StringBuilder();
        for (ParsedStatement.Check c : checks) b.append(c.ok() ? "OK" : "FAIL").append('|').append(c.label().replace('|', '/')).append('|').append(c.detail().replace("\n", " ")).append('\n');
        return b.toString();
    }

    private static Instant startOf(LocalDate d) {
        return d.atStartOfDay(Money.INDIA).toInstant();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
