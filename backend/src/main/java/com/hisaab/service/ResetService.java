package com.hisaab.service;

import com.hisaab.config.AppProperties;
import com.hisaab.config.Audit;
import com.hisaab.features.BackupService;
import com.hisaab.features.FeatureService;
import com.hisaab.features.FeatureService.Feature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Settings → Reset: clears the parts of the app I choose, after taking a backup. Never touches sign-in, the
 * backups folder, or how far mail reading has got (so old emails aren't imported again; Import older emails still
 * brings them back on purpose).
 */
@Service
public class ResetService {
    private static final Logger log = LoggerFactory.getLogger(ResetService.class);
    /** What I type to confirm. */
    public static final String WORD = "reset";

    public enum Part {
        /** Accounts, transactions, people, statements, investments, budgets, messages… */
        DATA,
        /** Rules, payee names, SMS formats and everything else learned from corrections. */
        LEARNED,
        /** My categories; the built-in ones come back. */
        CATEGORIES,
        /** Feature switches, my name, Home options. */
        SETTINGS,
        /** API tokens and the Pocket ID link. */
        CONNECTIONS
    }

    /**
     * Adds the parts a choice can't go without: learned rules point at accounts and saved messages, so clearing
     * the data clears them too; transactions use categories, so categories are only reset along with the data.
     */
    public static Set<Part> withNeeded(Collection<Part> chosen) {
        EnumSet<Part> s = chosen.isEmpty() ? EnumSet.noneOf(Part.class) : EnumSet.copyOf(chosen);
        if (s.contains(Part.CATEGORIES)) s.add(Part.DATA);
        if (s.contains(Part.DATA)) s.add(Part.LEARNED);
        return s;
    }

    /** Deletes per part, in an order that respects the foreign keys (children first). */
    static List<String> statements(Set<Part> parts) {
        List<String> sql = new ArrayList<>();
        if (parts.contains(Part.DATA)) {
            sql.addAll(List.of(
                    "delete from refund_watch", "delete from txn_tag", "delete from split_share", "delete from settlement",
                    "delete from alert", "delete from statement_import_line", "delete from statement_import",
                    "delete from statement_file", "delete from reward_rule", "delete from reward_program",
                    "delete from budget", "update txn set linked_txn_id = null", "delete from txn", "delete from tag",
                    "delete from person", "delete from account_hint", "delete from account", "delete from holding",
                    "delete from learned_example", "delete from raw_email", "delete from bill_ignore",
                    "delete from subscription_ignore", "delete from watch_dismiss", "delete from networth_snapshot",
                    "delete from app_setting where key like 'statement.password.%'"));
        }
        if (parts.contains(Part.LEARNED)) {
            sql.addAll(List.of(
                    "delete from category_rule where source <> 'SYSTEM'", "delete from category_vote",
                    "delete from payee_alias", "delete from account_hint", "delete from learned_example",
                    "update raw_email set template_id = null", "delete from sms_template", "delete from sms_sender",
                    "delete from trusted_sender"));
        }
        if (parts.contains(Part.CATEGORIES)) {
            sql.add("delete from category");   // its rules, votes and budgets go with it
        }
        if (parts.contains(Part.SETTINGS)) {
            sql.add("delete from app_setting where key like 'feature.%' or key like 'home.%' or key = '" + SettingsService.OWNER_NAME + "'");
        }
        if (parts.contains(Part.CONNECTIONS)) {
            sql.addAll(List.of("update txn set api_token_id = null", "delete from api_token",
                    "delete from app_setting where key like 'oidc.link.%'"));
        }
        return sql;
    }

    /** What each part would remove, so the sheet can say it before I agree. */
    public record Preview(long accounts, long transactions, long people, long statements, long holdings, long messages,
                          long learned, long categories, long settings, long tokens, boolean pocketIdLinked,
                          boolean backupsOn) {}

    /** @param backup the backup taken just before, or null when I chose to go without */
    public record Result(String backup) {}

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate tx;
    private final BackupService backups;
    private final FeatureService features;
    private final Path statementsDir;

    public ResetService(JdbcTemplate jdbc, DataSource dataSource, TransactionTemplate tx, BackupService backups,
                        FeatureService features, AppProperties props) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.tx = tx;
        this.backups = backups;
        this.features = features;
        this.statementsDir = Path.of(props.storage().dir()).toAbsolutePath().normalize().resolve("statements");
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    public Preview preview() {
        return new Preview(
                count("select count(*) from account"),
                count("select count(*) from txn"),
                count("select count(*) from person"),
                count("select count(*) from statement_file"),
                count("select count(*) from holding"),
                count("select count(*) from raw_email"),
                count("""
                        select (select count(*) from category_rule where source <> 'SYSTEM') + (select count(*) from category_vote)
                             + (select count(*) from payee_alias) + (select count(*) from account_hint)
                             + (select count(*) from learned_example) + (select count(*) from sms_template)
                             + (select count(*) from sms_sender) + (select count(*) from trusted_sender)"""),
                count("select count(*) from category"),
                count("select count(*) from app_setting where (key like 'feature.%' or key like 'home.%' or key = '"
                        + SettingsService.OWNER_NAME + "') and coalesce(value, '') <> ''"),
                count("select count(*) from api_token"),
                count("select count(*) from app_setting where key = 'oidc.link.id' and coalesce(value, '') <> ''") > 0,
                features.on(Feature.BACKUPS));
    }

    /**
     * Takes a backup (unless backups are off and I said to go without), then clears the chosen parts in one
     * transaction. Saved statement PDFs are deleted only once that has committed.
     */
    public synchronized Result reset(Collection<Part> chosen, String word, boolean withoutBackup) {
        if (chosen == null || chosen.isEmpty()) throw new IllegalArgumentException("Choose what to reset");
        if (word == null || !word.trim().equalsIgnoreCase(WORD)) throw new IllegalArgumentException("Type “" + WORD + "” to confirm");
        Set<Part> parts = withNeeded(chosen);

        String backup = null;
        if (features.on(Feature.BACKUPS)) {
            try {
                backup = backups.run().file();
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Couldn't take a backup first, so nothing was reset. " + e.getMessage());
            }
        } else if (!withoutBackup) {
            throw new IllegalStateException("Backups are off. Tick “Reset without a backup” to go ahead anyway.");
        }

        tx.executeWithoutResult(status -> {
            for (String sql : statements(parts)) jdbc.update(sql);
            if (parts.contains(Part.CATEGORIES)) {
                // the connection of this transaction, so the defaults come back in the same all-or-nothing step
                ScriptUtils.executeSqlScript(DataSourceUtils.getConnection(dataSource), new ClassPathResource("db/defaults/categories.sql"));
            }
        });
        if (parts.contains(Part.DATA)) deleteStatementFiles();
        Audit.log("Reset {} backup={}", parts.stream().map(p -> p.name().toLowerCase()).toList(), backup == null ? "none" : backup);
        return new Result(backup);
    }

    private void deleteStatementFiles() {
        if (!Files.isDirectory(statementsDir)) return;
        try (Stream<Path> s = Files.walk(statementsDir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(statementsDir)) Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // the database is already reset; leftover files only take space and are never shown
            log.warn("Reset: couldn't delete every saved statement file: {}", e.toString());
        }
    }
}
