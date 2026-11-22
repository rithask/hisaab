package com.hisaab.config;

import com.hisaab.common.util.Money;
import com.hisaab.settings.service.FeatureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map.Entry;
import java.util.stream.Collectors;

/** One line when the app is up: what's running and how it's set up, so a log shows it without opening the app. */
@Component
public class StartupLog {
    private static final Logger log = LoggerFactory.getLogger(StartupLog.class);

    private final AppProperties props;
    private final FeatureService features;
    private final String version;

    public StartupLog(AppProperties props, FeatureService features, @Value("${APP_VERSION:dev}") String version) {
        this.props = props;
        this.features = features;
        this.version = version;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        boolean oidc = props.oidc() != null && props.oidc().configured();
        String on = features.all().entrySet().stream().filter(e -> e.getValue()).map(Entry::getKey)
                .collect(Collectors.joining(","));
        log.info("Hisaab {} ready: timeZone={} passwordSignIn={} identityProvider={} mailbox={} prices={} features=[{}]",
                version, Money.ZONE.getId(), SecurityConfig.passwordLoginEnabled(props) ? "on" : "off",
                oidc ? props.oidc().name() : "none", props.mail() != null && props.mail().configured() ? "set up" : "not set up",
                props.prices() != null && props.prices().enabled() ? "on" : "off", on);
    }
}
