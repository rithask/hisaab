package com.hisaab.features;

import com.hisaab.config.Audit;
import com.hisaab.service.SettingsService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional features I can switch off in Settings. Off means: hidden in the app, and any background job for it
 * (backups) doesn't run. Only real choices are switches: what costs setup or space (budgets, rewards, backups) and
 * what lets other apps in (the Data API, off until I turn it on). Things that only watch my own data (bills,
 * subscriptions, unusual activity, net worth, year in review, search) are always on; switching them off saved
 * nothing and only hid useful answers. Their old stored switches are ignored.
 */
@Service
public class FeatureService {
    public enum Feature {
        BACKUPS, BUDGETS, REWARDS, DATA_API(false);

        final boolean onByDefault;

        Feature() {
            this(true);
        }

        Feature(boolean onByDefault) {
            this.onByDefault = onByDefault;
        }
    }

    private final SettingsService settings;

    public FeatureService(SettingsService settings) {
        this.settings = settings;
    }

    public boolean on(Feature f) {
        return Boolean.parseBoolean(settings.get(key(f)).orElse(Boolean.toString(f.onByDefault)));
    }

    /** Refuses the request (400 with a readable message) when the feature is switched off. */
    public void require(Feature f) {
        if (!on(f)) throw new IllegalStateException("This feature is turned off in Settings");
    }

    public void set(Feature f, boolean on) {
        settings.set(key(f), Boolean.toString(on));
    }

    /** All features and whether each is on, keyed by lower-case name (e.g. "bills", "data_api"). */
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
                Feature f = Feature.valueOf(k.toUpperCase());
                if (on(f) != v) Audit.log("Feature {} switched {}", f.name().toLowerCase(), v ? "on" : "off");
                set(f, v);
            } catch (IllegalArgumentException ignored) {
                // unknown feature names are ignored
            }
        });
    }

    private static String key(Feature f) {
        return "feature." + f.name().toLowerCase();
    }
}
