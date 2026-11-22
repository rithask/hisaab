package com.hisaab.settings.service;

import com.hisaab.settings.model.Feature;
import com.hisaab.common.util.Audit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hisaab.common.exception.NotAllowedException;
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
    private static final Logger log = LoggerFactory.getLogger(FeatureService.class);

    private final SettingsService settings;

    public FeatureService(SettingsService settings) {
        this.settings = settings;
    }

    public boolean on(Feature feature) {
        return Boolean.parseBoolean(settings.get(key(feature)).orElse(Boolean.toString(feature.onByDefault())));
    }

    /** Refuses the request (400 with a readable message) when the feature is switched off. */
    public void require(Feature f) {
        if (!on(f)) throw new NotAllowedException("This feature is turned off in Settings");
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
            } catch (IllegalArgumentException unknown) {
                // An older or newer app may send a feature this server doesn't have: skip it, but say so.
                log.warn("Ignored unknown feature \"{}\"", k);
            }
        });
    }

    private static String key(Feature f) {
        return "feature." + f.name().toLowerCase();
    }
}
