package com.hisaab.statements.controller;

import com.hisaab.common.dto.AccountRefResponse;
import com.hisaab.statements.dto.CommitImportRequest;
import com.hisaab.statements.dto.ImportAccountRequest;
import com.hisaab.statements.dto.ImportCommitResponse;
import com.hisaab.statements.dto.ImportFromFileRequest;
import com.hisaab.statements.dto.ImportLineResponse;
import com.hisaab.statements.dto.LineDecisionRequest;
import com.hisaab.statements.dto.MatchedTransactionResponse;
import com.hisaab.statements.dto.StatementImportResponse;
import com.hisaab.accounts.model.Account;
import com.hisaab.common.util.Audit;
import com.hisaab.common.util.Money;
import com.hisaab.statements.model.StatementFile;
import com.hisaab.statements.model.StatementImport;
import com.hisaab.statements.repository.StatementFileRepository;
import com.hisaab.statements.service.StatementImportService;
import com.hisaab.statements.service.StatementStorage;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.hisaab.common.exception.BadRequestException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static com.hisaab.common.exception.NotFound.notFound;

/** Statement import: upload → review (with my decisions) → import. */
@RestController
@RequestMapping("/api")
public class StatementImportController {
    private final StatementImportService service;
    private final StatementFileRepository statementFiles;
    private final StatementStorage storage;

    public StatementImportController(StatementImportService service, StatementFileRepository statementFiles, StatementStorage storage) {
        this.service = service;
        this.statementFiles = statementFiles;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    StatementImportResponse view(StatementImport imp) {
        Account a = imp.getAccount();
        List<ImportLineResponse> ls = service.lines(imp).stream().map(l -> new ImportLineResponse(l.getId(), l.getTxnDate(), l.getTxnTime(),
                l.getDescription(), l.getCounterparty(), l.getReferenceNo(), l.getDirection(), l.getAmount(), l.getBalance(),
                l.getUncertain(), l.getKind(), MatchedTransactionResponse.of(l.getMatchTxn()), l.getMatchReason(),
                l.getMatchTxn() == null ? List.of() : service.fills(l, l.getMatchTxn()), l.getDecision(),
                a != null && service.beforeStart(l, a), a == null ? null : service.balanceEffect(l, a))).toList();
        return new StatementImportResponse(imp.getId(), imp.getStatus().name(), imp.getLabel(), imp.getFileName(), imp.getInstitution().name(),
                imp.getAccountType().name(), imp.getLast4(), imp.getPeriodFrom(), imp.getPeriodTo(), imp.getOpeningBalance(),
                imp.getClosingBalance(), imp.getCreditLimit(), a == null ? null : AccountRefResponse.of(a),
                a == null ? null : a.getOpeningDate().atZone(Money.ZONE).toLocalDate(),
                StatementImportService.decodeChecks(imp.getChecks()), service.balanceCheck(imp).orElse(null), ls,
                service.notInStatement(imp).stream().map(MatchedTransactionResponse::of).toList());
    }

    @PostMapping("/statement-imports")
    public StatementImportResponse upload(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String password,
                             @RequestParam(defaultValue = "false") boolean remember) throws IOException {
        if (file.isEmpty()) throw new BadRequestException("Choose a statement PDF");
        return view(service.start(file.getBytes(), file.getOriginalFilename(), password, remember, null));
    }

    /** Import a statement that arrived by email and was saved under Statements. */
    @PostMapping("/statements/{id}/import")
    public StatementImportResponse fromSaved(@PathVariable Long id, @RequestBody(required = false) ImportFromFileRequest in) throws IOException {
        StatementFile f = statementFiles.findById(id).orElseThrow(notFound("Statement"));
        var open = service.openReviewFor(f);
        if (open.isPresent()) return view(open.get());
        byte[] pdf = Files.readAllBytes(storage.resolve(f.getStoredPath()));
        return view(service.start(pdf, f.getFileName(), in == null ? null : in.password(), in != null && Boolean.TRUE.equals(in.remember()), f));
    }

    @GetMapping("/statement-imports")
    @Transactional(readOnly = true)
    public List<StatementImportResponse> pending() {
        return service.pending().stream().map(this::view).toList();
    }

    @GetMapping("/statement-imports/{id}")
    public StatementImportResponse get(@PathVariable Long id) {
        return view(service.get(id));
    }

    @PutMapping("/statement-imports/{id}/account")
    public StatementImportResponse account(@PathVariable Long id, @RequestBody ImportAccountRequest in) {
        if (in.accountId() == null) throw new BadRequestException("Pick an account");
        return view(service.setAccount(id, in.accountId()));
    }

    @PutMapping("/statement-imports/{id}/lines/{lineId}")
    public StatementImportResponse decide(@PathVariable Long id, @PathVariable Long lineId, @RequestBody LineDecisionRequest in) {
        service.decide(id, lineId, in.decision());
        return view(service.get(id));
    }

    @PostMapping("/statement-imports/{id}/commit")
    public ImportCommitResponse commit(@PathVariable Long id, @RequestBody(required = false) CommitImportRequest in) {
        return service.commit(id, in != null && Boolean.TRUE.equals(in.fixStart()));
    }

    @DeleteMapping("/statement-imports/{id}")
    public void cancel(@PathVariable Long id) {
        service.cancel(id);
        Audit.log("Statement import cancelled id={}", id);
    }
}
