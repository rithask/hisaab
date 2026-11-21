package com.hisaab.settings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small settings (feature switches, my name, backup status …) in the app_setting table. The table is a few dozen
 * rows and is read on almost every request (feature switches), so it's kept in memory: read once, updated when a
 * change commits. Anything that writes the table without this class must call {@link #forget()}.
 */
@Service
public class SettingsService {
    public static final String OWNER_NAME = "owner.name";
    public static final String IMAP_UID_VALIDITY = "imap.uidValidity";
    public static final String IMAP_LAST_UID = "imap.lastUid";
    public static final String MAIL_LAST_SYNC = "mail.lastSync";
    public static final String MAIL_LAST_ERROR = "mail.lastError";
    /** Whether Home shows investments (in net worth and as a tile). */
    public static final String HOME_SHOW_INVESTMENTS = "home.showInvestments";

    private final AppSettingRepo repo;
    /** key → value; null until first read. Values may be null, so missing keys are simply absent. */
    private volatile Map<String, String> cache;

    public SettingsService(AppSettingRepo repo) {
        this.repo = repo;
    }

    private Map<String, String> all() {
        Map<String, String> c = cache;
        if (c == null) {
            c = new ConcurrentHashMap<>();
            for (AppSetting s : repo.findAll()) if (s.getValue() != null) c.put(s.getKey(), s.getValue());
            cache = c;
        }
        return c;
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(all().get(key));
    }

    /** Every setting whose key starts with the prefix (e.g. remembered statement passwords), sorted by key. */
    public Map<String, String> withPrefix(String prefix) {
        Map<String, String> out = new TreeMap<>();
        all().forEach((k, v) -> {
            if (k.startsWith(prefix)) out.put(k, v);
        });
        return out;
    }

    @Transactional
    public void set(String key, String value) {
        repo.save(new AppSetting(key, value));
        // only once it's saved for good: a change that's rolled back must not stay in memory
        Runnable remember = () -> {
            if (value == null) all().remove(key);
            else all().put(key, value);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    remember.run();
                }
            });
        } else {
            remember.run();
        }
    }

    /** Drop what's in memory; the next read loads the table again (after Reset changed it directly). */
    public void forget() {
        cache = null;
    }

    public String ownerName() {
        return get(OWNER_NAME).orElse(null);
    }
}
