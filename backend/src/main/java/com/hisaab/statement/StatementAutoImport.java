package com.hisaab.statement;

import com.hisaab.domain.StatementFile;
import com.hisaab.mail.StatementStorage;
import com.hisaab.repo.StatementFileRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

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
    private final StatementFileRepo files;
    private final StatementStorage storage;
    private final StatementAutoImport self;

    public StatementAutoImport(StatementImportService imports, StatementFileRepo files, StatementStorage storage,
                               @org.springframework.context.annotation.Lazy StatementAutoImport self) {
        this.imports = imports;
        this.files = files;
        this.storage = storage;
        this.self = self;
    }

    /** Runs after the email's own transaction has committed, so a problem here never loses the email. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onSaved(StatementSaved e) {
        try {
            self.read(e.statementFileId());
        } catch (RuntimeException ex) {
            log.warn("Couldn't read statement {} for import: {}", e.statementFileId(), ex.toString());
            self.mark(e.statementFileId(), "ERROR", "Couldn't read it: " + ex.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void read(Long fileId) {
        StatementFile f = files.findById(fileId).orElse(null);
        if (f == null) return;
        if (imports.openReviewFor(f).isPresent()) return;
        try {
            byte[] pdf = Files.readAllBytes(storage.resolve(f.getStoredPath()));
            imports.start(pdf, f.getFileName(), null, false, f);
        } catch (PdfText.PasswordRequired ex) {
            f.setImportStatus("NEEDS_PASSWORD");
            f.setImportNote("Needs its password once; after that, statements for this account open by themselves.");
        } catch (StatementImportService.UnsupportedStatement ex) {
            f.setImportStatus("UNSUPPORTED");
            f.setImportNote(ex.getMessage());
        } catch (java.io.IOException ex) {
            log.warn("Saved statement {} ({}) couldn't be read: {}", fileId, f.getStoredPath(), ex.toString());
            f.setImportStatus("ERROR");
            f.setImportNote("The saved file couldn't be read: " + ex.getMessage());
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
