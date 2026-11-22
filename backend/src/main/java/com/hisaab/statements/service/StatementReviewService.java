package com.hisaab.statements.service;

import com.hisaab.accounts.model.Account;
import com.hisaab.common.dto.AccountRefResponse;
import com.hisaab.common.exception.BadRequestException;
import com.hisaab.common.util.Audit;
import com.hisaab.common.util.Money;
import com.hisaab.statements.dto.CommitImportRequest;
import com.hisaab.statements.dto.ImportAccountRequest;
import com.hisaab.statements.dto.ImportCommitResponse;
import com.hisaab.statements.dto.ImportFromFileRequest;
import com.hisaab.statements.dto.ImportLineResponse;
import com.hisaab.statements.dto.LineDecisionRequest;
import com.hisaab.statements.dto.MatchedTransactionResponse;
import com.hisaab.statements.dto.StatementImportResponse;
import com.hisaab.statements.model.StatementFile;
import com.hisaab.statements.model.StatementImport;
import com.hisaab.statements.repository.StatementFileRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.hisaab.common.exception.NotFound.notFound;

/** Statement import: upload → review (with my decisions) → import. */
/**
 * A statement being reviewed before import: open one (uploaded, or saved from an email), show it line by line with
 * what each matches, take my decisions, and commit or cancel it.
 */
@Service
public class StatementReviewService {
    private final StatementImportService imports;
    private final StatementFileRepository statementFiles;
    private final StatementStorage storage;

    public StatementReviewService(StatementImportService imports, StatementFileRepository statementFiles, StatementStorage storage) {
        this.imports = imports;
        this.statementFiles = statementFiles;
        this.storage = storage;
    }

    private StatementImportResponse view(StatementImport imp) {
        Account a = imp.getAccount();
        List<ImportLineResponse> ls = imports.lines(imp).stream().map(l -> new ImportLineResponse(l.getId(), l.getTxnDate(), l.getTxnTime(),
                l.getDescription(), l.getCounterparty(), l.getReferenceNo(), l.getDirection(), l.getAmount(), l.getBalance(),
                l.getUncertain(), l.getKind(), MatchedTransactionResponse.of(l.getMatchTxn()), l.getMatchReason(),
                l.getMatchTxn() == null ? List.of() : imports.fills(l, l.getMatchTxn()), l.getDecision(),
                a != null && imports.beforeStart(l, a), a == null ? null : imports.balanceEffect(l, a))).toList();
        return new StatementImportResponse(imp.getId(), imp.getStatus().name(), imp.getLabel(), imp.getFileName(), imp.getInstitution().name(),
                imp.getAccountType().name(), imp.getLast4(), imp.getPeriodFrom(), imp.getPeriodTo(), imp.getOpeningBalance(),
                imp.getClosingBalance(), imp.getCreditLimit(), a == null ? null : AccountRefResponse.of(a),
                a == null ? null : a.getOpeningDate().atZone(Money.ZONE).toLocalDate(),
                StatementImportService.decodeChecks(imp.getChecks()), imports.balanceCheck(imp).orElse(null), ls,
                imports.notInStatement(imp).stream().map(MatchedTransactionResponse::of).toList());
    }

    /** Reads an uploaded statement PDF and opens it for review. */
    public StatementImportResponse upload(byte[] pdf, String fileName, String password, boolean remember) throws IOException {
        return view(imports.start(pdf, fileName, password, remember, null));
    }

    /** Import a statement that arrived by email and was saved under Statements. */
    public StatementImportResponse fromSaved(Long id, ImportFromFileRequest in) throws IOException {
        StatementFile statementFile = statementFiles.findById(id).orElseThrow(notFound("Statement"));
        var open = imports.openReviewFor(statementFile);
        if (open.isPresent()) return view(open.get());
        byte[] pdf = Files.readAllBytes(storage.resolve(statementFile.getStoredPath()));
        return view(imports.start(pdf, statementFile.getFileName(), in == null ? null : in.password(), in != null && Boolean.TRUE.equals(in.remember()), statementFile));
    }

    @Transactional(readOnly = true)
    public List<StatementImportResponse> pending() {
        return imports.pending().stream().map(this::view).toList();
    }

    public StatementImportResponse get(Long id) {
        return view(imports.get(id));
    }

    public StatementImportResponse account(Long id, ImportAccountRequest in) {
        if (in.accountId() == null) throw new BadRequestException("Pick an account");
        return view(imports.setAccount(id, in.accountId()));
    }

    public StatementImportResponse decide(Long id, Long lineId, LineDecisionRequest in) {
        imports.decide(id, lineId, in.decision());
        return view(imports.get(id));
    }

    public ImportCommitResponse commit(Long id, CommitImportRequest in) {
        return imports.commit(id, in != null && Boolean.TRUE.equals(in.fixStart()));
    }

    public void cancel(Long id) {
        imports.cancel(id);
        Audit.log("Statement import cancelled id={}", id);
    }
}
