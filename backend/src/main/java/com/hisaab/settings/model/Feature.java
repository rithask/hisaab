package com.hisaab.settings.model;

/** An optional feature I can switch off in Settings (see FeatureService). */
public enum Feature {
    BACKUPS, BUDGETS, REWARDS, DATA_API(false);

    private final boolean onByDefault;

    Feature() {
        this(true);
    }

    Feature(boolean onByDefault) {
        this.onByDefault = onByDefault;
    }

    public boolean onByDefault() {
        return onByDefault;
    }
}
