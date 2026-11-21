package com.hisaab.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Changes I made that matter later: settings switched, data deleted, a copy of the database downloaded. On their
 * own logger ("com.hisaab.audit") so they're easy to find: {@code docker compose logs app | grep audit}.
 * Ids and names of things only: never amounts, payees or message text.
 */
public final class Audit {
    private static final Logger log = LoggerFactory.getLogger("com.hisaab.audit");

    private Audit() {}

    public static void log(String format, Object... args) {
        log.info(format, args);
    }
}
