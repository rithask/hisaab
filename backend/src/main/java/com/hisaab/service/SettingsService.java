package com.hisaab.service;

import com.hisaab.domain.AppSetting;
import com.hisaab.repo.AppSettingRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

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

    public SettingsService(AppSettingRepo repo) {
        this.repo = repo;
    }

    public Optional<String> get(String key) {
        return repo.findById(key).map(AppSetting::getValue);
    }

    @Transactional
    public void set(String key, String value) {
        repo.save(new AppSetting(key, value));
    }

    public String ownerName() {
        return get(OWNER_NAME).orElse(null);
    }
}
