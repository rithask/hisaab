package com.hisaab.statements.service;

import com.hisaab.accounts.repository.AccountRepository;
import com.hisaab.common.exception.NotFoundException;
import com.hisaab.statements.dto.AssignAccountRequest;
import com.hisaab.statements.dto.StatementFileResponse;
import com.hisaab.statements.model.StatementFile;
import com.hisaab.statements.repository.StatementFileRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.hisaab.common.exception.NotFound.notFound;

/** Saved statement PDFs (Statements page): the list, the file itself, and which account one belongs to. */
@Service
public class StatementFileService {
    private final StatementFileRepository statements;
    private final StatementStorage storage;
    private final AccountRepository accounts;

    public StatementFileService(StatementFileRepository statements, StatementStorage storage, AccountRepository accounts) {
        this.statements = statements;
        this.storage = storage;
        this.accounts = accounts;
    }

    /** A saved PDF and the name it arrived with. */
    public record StoredPdf(String fileName, Resource content) {}

    @Transactional(readOnly = true)
    public List<StatementFileResponse> list() {
        return statements.findAllByOrderByReceivedAtDesc().stream().map(StatementFileResponse::of).toList();
    }

    public StoredPdf file(Long id) {
        StatementFile statement = statements.findById(id).orElseThrow(notFound("Statement"));
        Resource content = new FileSystemResource(storage.resolve(statement.getStoredPath()));
        if (!content.exists()) throw new NotFoundException("File is missing from storage");
        return new StoredPdf(statement.getFileName(), content);
    }

    @Transactional
    public StatementFileResponse assign(Long id, AssignAccountRequest in) {
        StatementFile statement = statements.findById(id).orElseThrow(notFound("Statement"));
        statement.setAccount(in.accountId() == null ? null : accounts.findById(in.accountId()).orElseThrow(notFound("Account")));
        return StatementFileResponse.of(statement);
    }
}
