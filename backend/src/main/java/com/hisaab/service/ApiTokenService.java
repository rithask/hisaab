package com.hisaab.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The single API token that lets an automation (an iPhone Shortcut, an Android SMS forwarder) post bank SMS.
 * Only a SHA-256 hash is stored; the token itself is shown once when generated. It grants access to the SMS
 * ingest endpoint and nothing else.
 */
@Service
public class ApiTokenService {
    private static final String HASH = "ingest.tokenHash";
    private static final String HINT = "ingest.tokenHint";
    private static final String CREATED = "ingest.tokenCreated";
    private static final String LAST_USED = "ingest.tokenLastUsed";
    private static final String PREFIX = "hisaab_";

    private final SettingsService settings;
    private final SecureRandom random = new SecureRandom();

    public ApiTokenService(SettingsService settings) {
        this.settings = settings;
    }

    public record Status(boolean enabled, String hint, String createdAt, String lastUsedAt) {}

    public Status status() {
        boolean on = settings.get(HASH).filter(s -> !s.isBlank()).isPresent();
        return new Status(on, on ? settings.get(HINT).orElse(null) : null,
                on ? settings.get(CREATED).orElse(null) : null, on ? settings.get(LAST_USED).orElse(null) : null);
    }

    /** Creates a new token, replacing (and invalidating) any previous one. */
    public String rotate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        settings.set(HASH, sha256(token));
        settings.set(HINT, token.substring(token.length() - 4));
        settings.set(CREATED, Instant.now().toString());
        settings.set(LAST_USED, "");
        return token;
    }

    public void revoke() {
        settings.set(HASH, "");
        settings.set(HINT, "");
    }

    public boolean verify(String presented) {
        if (presented == null || !presented.startsWith(PREFIX) || presented.length() > 200) return false;
        String stored = settings.get(HASH).orElse("");
        if (stored.isBlank()) return false;
        boolean ok = MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),
                sha256(presented).getBytes(StandardCharsets.US_ASCII));
        if (ok) settings.set(LAST_USED, Instant.now().toString());
        return ok;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
