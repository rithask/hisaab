package com.hisaab.settings.service;

import com.hisaab.settings.service.FeatureService.Feature;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An endpoint (or every endpoint of a controller) that belongs to an optional feature: while the feature is switched
 * off, the request is refused with "This feature is turned off in Settings" before the method runs. See
 * {@link FeatureGate} and docs/principles.md §3.6.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresFeature {
    Feature value();
}
