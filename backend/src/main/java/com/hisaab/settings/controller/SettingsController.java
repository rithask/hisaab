package com.hisaab.settings.controller;

import com.hisaab.settings.dto.MailStatusResponse;
import com.hisaab.settings.dto.SettingsRequest;
import com.hisaab.settings.dto.SettingsResponse;
import com.hisaab.common.util.Audit;
import com.hisaab.messages.service.MailSyncService;
import com.hisaab.settings.service.FeatureService;
import com.hisaab.settings.service.SettingsService;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping
    public SettingsResponse getSettings() {
        return new SettingsResponse(settings.ownerName(), new MailStatusResponse(mail.configured(),
                settings.get(SettingsService.MAIL_LAST_SYNC).orElse(null),
                settings.get(SettingsService.MAIL_LAST_ERROR).filter(s -> !s.isBlank()).orElse(null)),
                !"false".equals(settings.get(SettingsService.HOME_SHOW_INVESTMENTS).orElse("true")),
                features.all());
    }

    @PutMapping
    public SettingsResponse putSettings(@RequestBody SettingsRequest in) {
        if (in.ownerName() != null && !in.ownerName().trim().equals(settings.ownerName())) {
            settings.set(SettingsService.OWNER_NAME, in.ownerName().trim());
            Audit.log("Owner name changed");
        }
        features.update(in.features());
        if (in.showInvestmentsOnHome() != null) settings.set(SettingsService.HOME_SHOW_INVESTMENTS, in.showInvestmentsOnHome().toString());
        return getSettings();
    }
}
