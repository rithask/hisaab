package com.hisaab.statements.controller;

import com.hisaab.statements.dto.AssignAccountRequest;
import com.hisaab.statements.dto.StatementFileResponse;
import com.hisaab.statements.service.StatementFileService;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Saved statement PDFs (Statements page): the list, the file itself, and which account one belongs to. */
@RestController
@RequestMapping("/api/statements")
public class StatementController {
    private final StatementFileService statements;

    public StatementController(StatementFileService statements) {
        this.statements = statements;
    }

    @GetMapping
    public List<StatementFileResponse> statements() {
        return statements.list();
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        StatementFileService.StoredPdf pdf = statements.file(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(pdf.fileName()).build().toString())
                .body(pdf.content());
    }

    @PatchMapping("/{id}")
    public StatementFileResponse assign(@PathVariable Long id, @RequestBody AssignAccountRequest req) {
        return statements.assign(id, req);
    }
}
