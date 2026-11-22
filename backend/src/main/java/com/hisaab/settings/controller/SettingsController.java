package com.hisaab.settings.controller;

import com.hisaab.common.util.Audit;
import com.hisaab.messages.service.MailSyncService;
import com.hisaab.settings.service.FeatureService;
import com.hisaab.settings.service.SettingsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** The app's small settings (Settings → General and Features): my name, Home options, feature switches, mailbox status. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final SettingsService settings;
    private final FeatureService features;
    private final MailSyncService mail;

    public SettingsController(SettingsService settings, FeatureService features, MailSyncService mail) {
        this.settings = settings;
        this.features = features;
        this.mail = mail;
    }

    public record MailStatus(boolean configured, String lastSync, String lastError) {}

    public record SettingsDto(String ownerName, MailStatus mail, boolean showInvestmentsOnHome, Map<String, Boolean> features) {}

    @GetMapping
    public SettingsDto getSettings() {
        return new SettingsDto(settings.ownerName(), new MailStatus(mail.configured(),
                settings.get(SettingsService.MAIL_LAST_SYNC).orElse(null),
                settings.get(SettingsService.MAIL_LAST_ERROR).filter(s -> !s.isBlank()).orElse(null)),
                !"false".equals(settings.get(SettingsService.HOME_SHOW_INVESTMENTS).orElse("true")),
                features.all());
    }

    public record SettingsInput(String ownerName, Boolean showInvestmentsOnHome, Map<String, Boolean> features) {}

    @PutMapping
    public SettingsDto putSettings(@RequestBody SettingsInput in) {
        if (in.ownerName() != null && !in.ownerName().trim().equals(settings.ownerName())) {
            settings.set(SettingsService.OWNER_NAME, in.ownerName().trim());
            Audit.log("Owner name changed");
        }
        features.update(in.features());
        if (in.showInvestmentsOnHome() != null) settings.set(SettingsService.HOME_SHOW_INVESTMENTS, in.showInvestmentsOnHome().toString());
        return getSettings();
    }
}
