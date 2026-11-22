package com.hisaab.statements.service;

import com.hisaab.statements.model.StatementFile;
import com.hisaab.statements.parser.PdfText;
import com.hisaab.statements.repository.StatementFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import com.hisaab.statements.exception.PasswordRequiredException;
import com.hisaab.statements.exception.UnsupportedStatementException;
import java.nio.file.Files;

/**
 * Statements that arrive by email are read as soon as they're saved, using the passwords I've asked the app to
 * remember, and wait for my review like an uploaded one. Nothing is imported until I confirm. When no
 * remembered password opens the PDF, the statement is marked as needing one.
 */
@Component
public class StatementAutoImport {
    private static final Logger log = LoggerFactory.getLogger(StatementAutoImport.class);

    private final StatementImportService imports;
    private final StatementFileRepository files;
    private final StatementStorage storage;
    private final StatementAutoImport self;

    public StatementAutoImport(StatementImportService imports, StatementFileRepository files, StatementStorage storage,
                               @org.springframework.context.annotation.Lazy StatementAutoImport self) {
        this.imports = imports;
        this.files = files;
        this.storage = storage;
        this.self = self;
    }

    /** Runs after the email's own transaction has committed, so a problem here never loses the email. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onSaved(StatementSavedEvent statementSavedEvent) {
        try {
            self.read(statementSavedEvent.statementFileId());
        } catch (RuntimeException ex) {
            log.error("Couldn't read statement {} for import", statementSavedEvent.statementFileId(), ex);
            self.mark(statementSavedEvent.statementFileId(), "ERROR", "Couldn't read it: " + ex.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void read(Long fileId) {
        StatementFile statementFile = files.findById(fileId).orElse(null);
        if (statementFile == null) return;
        if (imports.openReviewFor(statementFile).isPresent()) return;
        try {
            byte[] pdf = Files.readAllBytes(storage.resolve(statementFile.getStoredPath()));
            imports.start(pdf, statementFile.getFileName(), null, false, statementFile);
        } catch (PasswordRequiredException ex) {
            log.info("Statement {} from email needs its password before it can be read", fileId);
            statementFile.setImportStatus("NEEDS_PASSWORD");
            statementFile.setImportNote("Needs its password once; after that, statements for this account open by themselves.");
        } catch (UnsupportedStatementException ex) {
            log.info("Statement {} from email isn't one the app can read: {}", fileId, ex.getMessage());
            statementFile.setImportStatus("UNSUPPORTED");
            statementFile.setImportNote(ex.getMessage());
        } catch (java.io.IOException ex) {
            log.warn("Saved statement {} ({}) couldn't be read: {}", fileId, statementFile.getStoredPath(),
                    ex.toString());
            statementFile.setImportStatus("ERROR");
            statementFile.setImportNote("The saved file couldn't be read: " + ex.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void mark(Long fileId, String status, String note) {
        files.findById(fileId).ifPresent(f -> {
            f.setImportStatus(status);
            f.setImportNote(note);
        });
    }
}
