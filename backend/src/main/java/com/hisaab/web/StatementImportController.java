package com.hisaab.web;

import com.hisaab.domain.Account;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.LineDecision;
import com.hisaab.domain.Enums.LineKind;
import com.hisaab.domain.StatementFile;
import com.hisaab.domain.StatementImport;
import com.hisaab.domain.StatementImportLine;
import com.hisaab.domain.Txn;
import com.hisaab.mail.StatementStorage;
import com.hisaab.repo.StatementFileRepo;
import com.hisaab.statement.ParsedStatement;
import com.hisaab.statement.StatementImportService;
import com.hisaab.statement.StatementImportService.BalanceCheck;
import com.hisaab.statement.StatementImportService.Fill;
import com.hisaab.web.Dtos.AccountRef;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.NoSuchElementException;

/** Statement import: upload → review (with my decisions) → import. */
@RestController
@RequestMapping("/api")
public class StatementImportController {
    private final StatementImportService service;
    private final StatementFileRepo statementFiles;
    private final StatementStorage storage;

    public StatementImportController(StatementImportService service, StatementFileRepo statementFiles, StatementStorage storage) {
        this.service = service;
        this.statementFiles = statementFiles;
        this.storage = storage;
    }

    public record TxnBrief(Long id, Direction direction, BigDecimal amount, Instant occurredAt, boolean timeEstimated,
                           String counterparty, String referenceNo, String source) {
        static TxnBrief of(Txn t) {
            return t == null ? null : new TxnBrief(t.getId(), t.getDirection(), t.getAmount(), t.getOccurredAt(), t.isTimeEstimated(),
                    t.getCounterparty(), t.getReferenceNo(), t.getSource().name());
        }
    }

    public record LineView(Long id, LocalDate date, LocalTime time, String description, String counterparty, String reference,
                           Direction direction, BigDecimal amount, BigDecimal balance, String uncertain, LineKind kind,
                           TxnBrief match, String matchReason, List<Fill> fills, LineDecision decision,
                           boolean beforeStart, BigDecimal balanceEffect) {}

    public record ImportView(Long id, String status, String label, String fileName, String institution, String accountType,
                             String last4, LocalDate from, LocalDate to, BigDecimal opening, BigDecimal closing,
                             BigDecimal creditLimit, AccountRef account, LocalDate accountStart,
                             List<ParsedStatement.Check> checks, BalanceCheck balance, List<LineView> lines,
                             List<TxnBrief> notInStatement) {}

    @Transactional(readOnly = true)
    ImportView view(StatementImport imp) {
        Account a = imp.getAccount();
        List<LineView> ls = service.lines(imp).stream().map(l -> new LineView(l.getId(), l.getTxnDate(), l.getTxnTime(),
                l.getDescription(), l.getCounterparty(), l.getReferenceNo(), l.getDirection(), l.getAmount(), l.getBalance(),
                l.getUncertain(), l.getKind(), TxnBrief.of(l.getMatchTxn()), l.getMatchReason(),
                l.getMatchTxn() == null ? List.of() : service.fills(l, l.getMatchTxn()), l.getDecision(),
                a != null && service.beforeStart(l, a), a == null ? null : service.balanceEffect(l, a))).toList();
        return new ImportView(imp.getId(), imp.getStatus().name(), imp.getLabel(), imp.getFileName(), imp.getInstitution().name(),
                imp.getAccountType().name(), imp.getLast4(), imp.getPeriodFrom(), imp.getPeriodTo(), imp.getOpeningBalance(),
                imp.getClosingBalance(), imp.getCreditLimit(), a == null ? null : AccountRef.of(a),
                a == null ? null : a.getOpeningDate().atZone(com.hisaab.service.Money.ZONE).toLocalDate(),
                StatementImportService.decodeChecks(imp.getChecks()), service.balanceCheck(imp).orElse(null), ls,
                service.notInStatement(imp).stream().map(TxnBrief::of).toList());
    }

    @PostMapping("/statement-imports")
    public ImportView upload(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String password,
                             @RequestParam(defaultValue = "false") boolean remember) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("Choose a statement PDF");
        return view(service.start(file.getBytes(), file.getOriginalFilename(), password, remember, null));
    }

    public record FromStatementFile(String password, Boolean remember) {}

    /** Import a statement that arrived by email and was saved under Statements. */
    @PostMapping("/statements/{id}/import")
    public ImportView fromSaved(@PathVariable Long id, @RequestBody(required = false) FromStatementFile in) throws IOException {
        StatementFile f = statementFiles.findById(id).orElseThrow(() -> new NoSuchElementException("Statement not found"));
        var open = service.openReviewFor(f);
        if (open.isPresent()) return view(open.get());
        byte[] pdf = Files.readAllBytes(storage.resolve(f.getStoredPath()));
        return view(service.start(pdf, f.getFileName(), in == null ? null : in.password(), in != null && Boolean.TRUE.equals(in.remember()), f));
    }

    @GetMapping("/statement-imports")
    @Transactional(readOnly = true)
    public List<ImportView> pending() {
        return service.pending().stream().map(this::view).toList();
    }

    @GetMapping("/statement-imports/{id}")
    public ImportView get(@PathVariable Long id) {
        return view(service.get(id));
    }

    public record AccountChoice(Long accountId) {}

    @PutMapping("/statement-imports/{id}/account")
    public ImportView account(@PathVariable Long id, @RequestBody AccountChoice in) {
        if (in.accountId() == null) throw new IllegalArgumentException("Pick an account");
        return view(service.setAccount(id, in.accountId()));
    }

    public record DecisionIn(LineDecision decision) {}

    @PutMapping("/statement-imports/{id}/lines/{lineId}")
    public ImportView decide(@PathVariable Long id, @PathVariable Long lineId, @RequestBody DecisionIn in) {
        service.decide(id, lineId, in.decision());
        return view(service.get(id));
    }

    public record CommitIn(Boolean fixStart) {}

    @PostMapping("/statement-imports/{id}/commit")
    public StatementImportService.Result commit(@PathVariable Long id, @RequestBody(required = false) CommitIn in) {
        return service.commit(id, in != null && Boolean.TRUE.equals(in.fixStart()));
    }

    @DeleteMapping("/statement-imports/{id}")
    public void cancel(@PathVariable Long id) {
        service.cancel(id);
    }
}
