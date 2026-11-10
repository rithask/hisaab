package com.hisaab.mail;

import com.hisaab.config.AppProperties;
import com.hisaab.domain.Account;
import com.hisaab.service.Money;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/** Saves statement PDFs as-is under {storage}/statements/{account}/{yyyy-MM}_{file}. */
@Component
public class StatementStorage {
    private final Path root;

    public StatementStorage(AppProperties props) {
        this.root = Path.of(props.storage().dir()).toAbsolutePath().normalize();
    }

    public Path save(Account account, Instant receivedAt, String fileName, byte[] data) throws IOException {
        String folder = account == null ? "unassigned" : slug(account.getName()) + "-" + account.getId();
        String month = DateTimeFormatter.ofPattern("yyyy-MM").withZone(Money.ZONE).format(receivedAt);
        Path dir = root.resolve("statements").resolve(folder);
        Files.createDirectories(dir);
        String safe = fileName == null ? "statement.pdf" : fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        Path target = dir.resolve(month + "_" + safe);
        int n = 1;
        while (Files.exists(target)) {
            target = dir.resolve(month + "_" + (n++) + "_" + safe);
        }
        Files.write(target, data);
        return target;
    }

    /** What to store in the database for a saved file: its path relative to the storage folder. */
    public String toStored(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString();
    }

    /**
     * The file for a stored path. Accepts relative paths (current) and absolute ones written by older versions,
     * which are re-rooted at "statements/" so data moved to another machine or container still resolves.
     */
    public Path resolve(String stored) {
        Path given = Path.of(stored);
        Path p;
        if (!given.isAbsolute()) p = root.resolve(given).normalize();
        else if (given.normalize().startsWith(root)) p = given.normalize();
        else {
            String s = given.toString();
            int i = s.indexOf("statements/");
            p = i >= 0 ? root.resolve(s.substring(i)).normalize() : given.normalize();
        }
        if (!p.startsWith(root)) throw new IllegalArgumentException("Path outside storage");
        return p;
    }

    private static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
