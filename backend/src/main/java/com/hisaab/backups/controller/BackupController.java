package com.hisaab.backups.controller;

import com.hisaab.backups.dto.BackupResponse;
import com.hisaab.backups.dto.BackupStatusResponse;
import com.hisaab.settings.model.Feature;
import com.hisaab.backups.service.BackupService;
import com.hisaab.common.util.Audit;
import com.hisaab.settings.service.RequiresFeature;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;

/** Backups: status, back up now, and downloading one (optional feature). */
@RestController
@RequestMapping("/api")
public class BackupController {
    private final BackupService backups;

    public BackupController(BackupService backups) {
        this.backups = backups;
    }

    // ---- backups
    @GetMapping("/backups")
    @RequiresFeature(Feature.BACKUPS)
    public BackupStatusResponse backupStatus() throws IOException {
        return backups.status();
    }

    @PostMapping("/backups")
    @RequiresFeature(Feature.BACKUPS)
    public BackupResponse backupNow() throws IOException {
        Audit.log("Backup started by hand");
        return backups.run();
    }

    @GetMapping("/backups/{file}")
    @RequiresFeature(Feature.BACKUPS)
    public ResponseEntity<Resource> download(@PathVariable String file, jakarta.servlet.http.HttpServletRequest req) {
        Path path = backups.file(file);
        // a full copy of the database leaving the server
        Audit.log("Backup downloaded file={} ip={}", path.getFileName(), req.getRemoteAddr());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .body(new FileSystemResource(path));
    }
}
