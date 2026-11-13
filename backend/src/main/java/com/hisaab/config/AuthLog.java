package com.hisaab.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sign-in and API-token events, on their own logger ("com.hisaab.security") so they're easy to filter:
 * {@code docker compose logs app | grep security}. Never logs passwords or tokens.
 * <p>The client IP is the one Caddy reports in X-Forwarded-For (server.forward-headers-strategy=framework), so
 * it's only trustworthy while the app is reachable solely through the proxy.
 */
public final class AuthLog {
    private static final Logger log = LoggerFactory.getLogger("com.hisaab.security");

    private AuthLog() {}

    public static void signIn(HttpServletRequest req, String user) {
        signIn(req, user, "password");
    }

    public static void signIn(HttpServletRequest req, String user, String method) {
        log.info("Sign-in OK user=\"{}\" via={} ip={} agent=\"{}\"", clean(user, 64), clean(method, 32), req.getRemoteAddr(), agent(req));
    }

    public static void signInFailed(HttpServletRequest req, String user) {
        signInFailed(req, user, "password");
    }

    public static void signInFailed(HttpServletRequest req, String user, String reason) {
        log.warn("Sign-in FAILED user=\"{}\" via=\"{}\" ip={} agent=\"{}\"", clean(user, 64), clean(reason, 160), req.getRemoteAddr(), agent(req));
    }

    public static void signOut(HttpServletRequest req, String user) {
        log.info("Sign-out user=\"{}\" ip={}", clean(user, 64), req.getRemoteAddr());
    }

    public static void tokenRejected(HttpServletRequest req, int recentFailures) {
        log.warn("API token REJECTED ip={} agent=\"{}\" failures-in-15min={}", req.getRemoteAddr(), agent(req), recentFailures);
    }

    public static void tokenLockedOut(HttpServletRequest req) {
        log.warn("API LOCKED OUT ip={} for 15 minutes after repeated invalid tokens", req.getRemoteAddr());
    }

    /**
     * One line per API request that presented a valid token: info when it worked, warn when it was refused (4xx,
     * with the reason the client was given), error when it failed on our side (5xx).
     */
    public static void tokenUsed(HttpServletRequest req, String token, int status) {
        String line = "API token used token=\"{}\" {} {} status={} ip={} reason=\"{}\"";
        Object[] args = {clean(token, 64), req.getMethod(), clean(req.getRequestURI(), 100), status, req.getRemoteAddr(), reason(req)};
        if (status >= 500) log.error(line, args);
        else if (status >= 400) log.warn(line, args);
        else log.info(line.substring(0, line.indexOf(" reason")), args);
    }

    /** An API request refused before any token was checked: none sent, or too many requests. */
    public static void apiRefused(HttpServletRequest req, int status) {
        log.warn("API request REFUSED {} {} status={} ip={} agent=\"{}\" reason=\"{}\"", req.getMethod(),
                clean(req.getRequestURI(), 100), status, req.getRemoteAddr(), agent(req), reason(req));
    }

    private static String reason(HttpServletRequest req) {
        Object r = req.getAttribute(ApiTokenFilter.ERROR);
        return clean(r == null ? null : r.toString(), 200);
    }

    /** Printable, single-line, length-capped: a username can't forge extra log lines. */
    static String clean(String s, int max) {
        if (s == null || s.isBlank()) return "-";
        String v = s.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        v = v.replace('"', '\'');
        return v.length() > max ? v.substring(0, max) + "…" : v;
    }

    private static String agent(HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        return ua == null ? "-" : clean(ua, 120).replace('"', '\'');
    }
}
