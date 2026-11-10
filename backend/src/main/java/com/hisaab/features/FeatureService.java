package com.hisaab.features;

import com.hisaab.service.SettingsService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional features I can switch off in Settings. Off means: hidden in the app, and any background job for it
 * (backups, net-worth snapshots) doesn't run. Everything is on by default.
 */
@Service
public class FeatureService {
    public enum Feature { BACKUPS, BILLS, BUDGETS, NETWORTH, REVIEW, SEARCH, SUBSCRIPTIONS, UNUSUAL, REWARDS }

    private final SettingsService settings;

    public FeatureService(SettingsService settings) {
        this.settings = settings;
    }

    public boolean on(Feature f) {
        return !"false".equals(settings.get(key(f)).orElse("true"));
    }

    /** Refuses the request (400 with a readable message) when the feature is switched off. */
    public void require(Feature f) {
        if (!on(f)) throw new IllegalStateException("This feature is turned off in Settings");
    }

    public void set(Feature f, boolean on) {
        settings.set(key(f), Boolean.toString(on));
    }

    /** All features and whether each is on, keyed by lower-case name (e.g. "bills"). */
    public Map<String, Boolean> all() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (Feature f : Feature.values()) m.put(f.name().toLowerCase(), on(f));
        return m;
    }

    public void update(Map<String, Boolean> changes) {
        if (changes == null) return;
        changes.forEach((k, v) -> {
            if (v == null) return;
            try {
                set(Feature.valueOf(k.toUpperCase()), v);
            } catch (IllegalArgumentException ignored) {
                // unknown feature names are ignored
            }
        });
    }

    private static String key(Feature f) {
        return "feature." + f.name().toLowerCase();
    }
}
