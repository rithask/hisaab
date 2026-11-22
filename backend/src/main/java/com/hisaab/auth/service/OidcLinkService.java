package com.hisaab.auth.service;

import com.hisaab.auth.dto.OidcLinkResponse;
import com.hisaab.settings.service.SettingsService;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.time.Instant;

/**
 * Which identity-provider account is linked to this (single-user) app. The first OIDC sign-in doesn't get in
 * straight away: it waits as "pending" until I confirm linking (with my app password while password sign-in is
 * on). After that only that same account, identified by issuer + subject, can sign in with the provider.
 */
@Service
public class OidcLinkService {
    public static final String PENDING = "hisaab.oidc.pending";
    private static final String ID = "oidc.link.id", EMAIL = "oidc.link.email", AT = "oidc.link.at";

    private final SettingsService settings;

    public OidcLinkService(SettingsService settings) {
        this.settings = settings;
    }

    /** An identity waiting for me to confirm linking; kept in the browser session only. */
    public record Pending(String id, String email, String name) implements Serializable {}

    public String linkedId() {
        return settings.get(ID).filter(s -> !s.isBlank()).orElse(null);
    }

    public OidcLinkResponse link() {
        String id = linkedId();
        return new OidcLinkResponse(id != null, id == null ? null : settings.get(EMAIL).orElse(null),
                id == null ? null : settings.get(AT).filter(s -> !s.isBlank()).map(Instant::parse).orElse(null));
    }

    public void save(Pending pending) {
        settings.set(ID, pending.id());
        settings.set(EMAIL, pending.email() == null ? "" : pending.email());
        settings.set(AT, Instant.now().toString());
    }

    public void unlink() {
        settings.set(ID, "");
        settings.set(EMAIL, "");
        settings.set(AT, "");
    }
}
