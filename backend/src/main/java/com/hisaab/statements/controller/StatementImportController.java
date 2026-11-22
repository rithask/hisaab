package com.hisaab.statements.controller;

import com.hisaab.statements.service.StatementReviewService;
import com.hisaab.statements.dto.CommitImportRequest;
import com.hisaab.statements.dto.ImportAccountRequest;
import com.hisaab.statements.dto.ImportCommitResponse;
import com.hisaab.statements.dto.ImportFromFileRequest;
import com.hisaab.statements.dto.LineDecisionRequest;
import com.hisaab.statements.dto.StatementImportResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.hisaab.common.exception.BadRequestException;
import java.io.IOException;
import java.util.List;

/** Statement import: upload → review (with my decisions) → import. */
@RestController
@RequestMapping("/api")
public class StatementImportController {
    private final StatementReviewService reviews;

    public StatementImportController(StatementReviewService reviews) {
        this.reviews = reviews;
    }

    @PostMapping("/statement-imports")
    public StatementImportResponse upload(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String password,
                             @RequestParam(defaultValue = "false") boolean remember) throws IOException {
        if (file.isEmpty()) throw new BadRequestException("Choose a statement PDF");
        return reviews.upload(file.getBytes(), file.getOriginalFilename(), password, remember);
    }

    /** Import a statement that arrived by email and was saved under Statements. */
    @PostMapping("/statements/{id}/import")
    public StatementImportResponse fromSaved(@PathVariable Long id, @RequestBody(required = false) ImportFromFileRequest in) throws IOException {
        return reviews.fromSaved(id, in);
    }

    @GetMapping("/statement-imports")
    public List<StatementImportResponse> pending() {
        return reviews.pending();
    }

    @GetMapping("/statement-imports/{id}")
    public StatementImportResponse get(@PathVariable Long id) {
        return reviews.get(id);
    }

    @PutMapping("/statement-imports/{id}/account")
    public StatementImportResponse account(@PathVariable Long id, @RequestBody ImportAccountRequest in) {
        return reviews.account(id, in);
    }

    @PutMapping("/statement-imports/{id}/lines/{lineId}")
    public StatementImportResponse decide(@PathVariable Long id, @PathVariable Long lineId, @RequestBody LineDecisionRequest in) {
        return reviews.decide(id, lineId, in);
    }

    @PostMapping("/statement-imports/{id}/commit")
    public ImportCommitResponse commit(@PathVariable Long id, @RequestBody(required = false) CommitImportRequest in) {
        return reviews.commit(id, in);
    }

    @DeleteMapping("/statement-imports/{id}")
    public void cancel(@PathVariable Long id) {
        reviews.cancel(id);
    }
}
