package com.hisaab.statements.controller;

import com.hisaab.accounts.repository.AccountRepository;
import com.hisaab.common.dto.Dtos.StatementDto;
import com.hisaab.statements.model.StatementFile;
import com.hisaab.statements.repository.StatementFileRepository;
import com.hisaab.statements.service.StatementStorage;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.hisaab.common.exception.NotFoundException;
import java.util.List;
import java.util.NoSuchElementException;

import static com.hisaab.common.exception.NotFound.notFound;

/** Saved statement PDFs (Statements page): the list, the file itself, and which account one belongs to. */
@RestController
@RequestMapping("/api/statements")
public class StatementController {
    private final StatementFileRepository statements;
    private final StatementStorage storage;
    private final AccountRepository accounts;

    public StatementController(StatementFileRepository statements, StatementStorage storage, AccountRepository accounts) {
        this.statements = statements;
        this.storage = storage;
        this.accounts = accounts;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<StatementDto> statements() {
        return statements.findAllByOrderByReceivedAtDesc().stream().map(StatementDto::of).toList();
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        StatementFile s = statements.findById(id).orElseThrow(notFound("Statement"));
        Resource file = new FileSystemResource(storage.resolve(s.getStoredPath()));
        if (!file.exists()) throw new NotFoundException("File is missing from storage");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(s.getFileName()).build().toString())
                .body(file);
    }

    public record AssignRequest(Long accountId) {}

    @PatchMapping("/{id}")
    @Transactional
    public StatementDto assign(@PathVariable Long id, @RequestBody AssignRequest req) {
        StatementFile s = statements.findById(id).orElseThrow(notFound("Statement"));
        s.setAccount(req.accountId() == null ? null : accounts.findById(req.accountId()).orElseThrow(notFound("Account")));
        return StatementDto.of(s);
    }
}
