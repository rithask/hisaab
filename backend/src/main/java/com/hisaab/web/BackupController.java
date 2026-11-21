package com.hisaab.web;

import com.hisaab.config.Audit;
import com.hisaab.features.*;
import com.hisaab.features.FeatureService.Feature;
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
    public BackupService.Status backupStatus() throws IOException {
        return backups.status();
    }

    @PostMapping("/backups")
    @RequiresFeature(Feature.BACKUPS)
    public BackupService.Backup backupNow() throws IOException {
        Audit.log("Backup started by hand");
        return backups.run();
    }

    @GetMapping("/backups/{file}")
    @RequiresFeature(Feature.BACKUPS)
    public ResponseEntity<Resource> download(@PathVariable String file, jakarta.servlet.http.HttpServletRequest req) {
        Path p = backups.file(file);
        // a full copy of the database leaving the server
        Audit.log("Backup downloaded file={} ip={}", p.getFileName(), req.getRemoteAddr());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .body(new FileSystemResource(p));
    }
}
