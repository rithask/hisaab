package com.hisaab.features;

import com.hisaab.features.FeatureService.Feature;
import com.hisaab.service.Money;
import com.hisaab.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Automatic backups: one zip per run with the whole database (pg_dump, plain SQL) and every saved statement PDF,
 * plus a RESTORE.txt. Runs nightly (BACKUP_CRON), keeps the newest BACKUP_KEEP, and can be run on demand.
 */
@Service
public class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    static final String PREFIX = "hisaab-";

    private final FeatureService features;
    private final SettingsService settings;
    private final Path dir;
    private final Path storage;
    private final int keep;
    private final String pgDump;
    private final String dbUrl, dbUser, dbPassword;

    public BackupService(FeatureService features, SettingsService settings,
                         @Value("${app.backup.dir:./backups}") String dir,
                         @Value("${app.storage.dir:./data}") String storage,
                         @Value("${app.backup.keep:14}") int keep,
                         @Value("${app.backup.pg-dump:pg_dump}") String pgDump,
                         @Value("${spring.datasource.url}") String dbUrl,
                         @Value("${spring.datasource.username:}") String dbUser,
                         @Value("${spring.datasource.password:}") String dbPassword) {
        this.features = features;
        this.settings = settings;
        this.dir = Path.of(dir).toAbsolutePath().normalize();
        this.storage = Path.of(storage).toAbsolutePath().normalize();
        this.keep = Math.max(1, keep);
        this.pgDump = pgDump;
        this.dbUrl = dbUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
    }

    public record Backup(String file, long sizeBytes, Instant createdAt) {}

    /** @param stale the last good backup is older than two days (or there's none while the feature is on) */
    public record Status(boolean enabled, String dir, int keep, Instant lastOk, String lastError, Instant lastErrorAt,
                         boolean stale, List<Backup> backups) {}

    @Scheduled(cron = "${app.backup.cron:0 30 3 * * *}")   // your time zone (TZ)
    public void nightly() {
        if (!features.on(Feature.BACKUPS)) return;
        try {
            run();
        } catch (IOException | RuntimeException e) {
            // already recorded in settings by run()
        }
    }

    /** Makes a backup now, then trims old ones. Synchronized: never two at once. */
    public synchronized Backup run() throws IOException {
        Files.createDirectories(dir);
        String name = PREFIX + LocalDateTime.now(Money.ZONE).format(STAMP) + ".zip";
        Path tmp = dir.resolve(name + ".part");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                zip.putNextEntry(new ZipEntry("database.sql"));
                dumpDatabase(zip);
                zip.closeEntry();
                addFiles(zip, storage.resolve("statements"), "files/statements/");
                zip.putNextEntry(new ZipEntry("RESTORE.txt"));
                zip.write(restoreNote().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            Path target = dir.resolve(name);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            settings.set("backup.lastOk", Instant.now().toString());
            settings.set("backup.lastError", "");
            trim();
            long size = Files.size(target);
            log.info("Backup written: {} ({} KB)", target, size / 1024);
            return new Backup(name, size, Instant.now());
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            settings.set("backup.lastError", e.getMessage() == null ? e.toString() : e.getMessage());
            settings.set("backup.lastErrorAt", Instant.now().toString());
            log.warn("Backup failed: {}", e.toString());
            throw e;
        }
    }

    private void dumpDatabase(OutputStream out) throws IOException {
        URI u = URI.create(dbUrl.replaceFirst("^jdbc:", ""));
        String db = u.getPath().replaceFirst("^/", "");
        List<String> cmd = new ArrayList<>(List.of(pgDump, "--no-owner", "--no-acl", "--clean", "--if-exists",
                "-h", u.getHost() == null ? "localhost" : u.getHost(), "-p", String.valueOf(u.getPort() > 0 ? u.getPort() : 5432)));
        if (dbUser != null && !dbUser.isBlank()) cmd.addAll(List.of("-U", dbUser));
        cmd.add(db);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (dbPassword != null && !dbPassword.isBlank()) pb.environment().put("PGPASSWORD", dbPassword);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("Couldn't run pg_dump (" + pgDump + "): " + e.getMessage());
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().start(() -> {
            try (InputStream es = p.getErrorStream()) {
                es.transferTo(err);
            } catch (IOException ignored) {
                // nothing to report
            }
        });
        try (InputStream in = p.getInputStream()) {
            in.transferTo(out);
        }
        try {
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("pg_dump took longer than 10 minutes");
            }
            drain.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Backup interrupted");
        }
        if (p.exitValue() != 0) throw new IOException("pg_dump failed: " + err.toString(StandardCharsets.UTF_8).trim());
    }

    private static void addFiles(ZipOutputStream zip, Path root, String prefix) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(prefix + root.relativize(f).toString().replace('\\', '/')));
                Files.copy(f, zip);
                zip.closeEntry();
            }
        }
    }

    private static String restoreNote() {
        return """
                Hisaab backup
                ====================
                database.sql        the whole database (plain SQL; it drops and recreates every table)
                files/statements/   saved statement PDFs

                Restore with Docker (from the folder with compose.yaml):
                  docker compose stop app
                  unzip hisaab-<date>.zip -d restore
                  docker compose exec -T db psql -U hisaab -d hisaab < restore/database.sql
                  docker compose cp restore/files/. app:/data/
                  docker compose start app
                """;
    }

    private void trim() throws IOException {
        List<Path> all = list().stream().map(b -> dir.resolve(b.file())).toList();
        for (int i = keep; i < all.size(); i++) Files.deleteIfExists(all.get(i));
    }

    /** Newest first. */
    public List<Backup> list() throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(PREFIX) && p.getFileName().toString().endsWith(".zip"))
                    .map(p -> {
                        try {
                            return new Backup(p.getFileName().toString(), Files.size(p), Files.getLastModifiedTime(p).toInstant());
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(Backup::file).reversed())
                    .toList();
        }
    }

    public Path file(String name) {
        if (!name.matches(PREFIX + "[0-9-]+\\.zip")) throw new IllegalArgumentException("Not a backup file");
        Path p = dir.resolve(name).normalize();
        if (!p.startsWith(dir) || !Files.isRegularFile(p)) throw new NoSuchElementException("Backup not found");
        return p;
    }

    public Status status() throws IOException {
        Instant ok = settings.get("backup.lastOk").filter(s -> !s.isBlank()).map(Instant::parse).orElse(null);
        String err = settings.get("backup.lastError").filter(s -> !s.isBlank()).orElse(null);
        Instant errAt = err == null ? null : settings.get("backup.lastErrorAt").filter(s -> !s.isBlank()).map(Instant::parse).orElse(null);
        boolean on = features.on(Feature.BACKUPS);
        boolean stale = on && (ok == null || ok.isBefore(Instant.now().minusSeconds(48 * 3600)));
        return new Status(on, dir.toString(), keep, ok, err, errAt, stale, list());
    }

    /** True when Home should nag: the feature is on and the last run failed or nothing succeeded in two days. */
    public boolean needsAttention() {
        try {
            Status s = status();
            boolean failedLast = s.lastError() != null && (s.lastOk() == null || (s.lastErrorAt() != null && s.lastErrorAt().isAfter(s.lastOk())));
            return s.enabled() && (failedLast || (s.stale() && s.lastOk() != null));
        } catch (IOException e) {
            return true;
        }
    }
}
