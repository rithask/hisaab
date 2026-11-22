package com.hisaab.config;

import com.hisaab.auth.util.AuthLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/**
 * Refuses changes (POST, PUT, PATCH, DELETE) to the app's own API from any page but the app's own, which is what
 * stands between the session cookie and cross-site request forgery. SameSite=Lax keeps other sites out, but
 * every subdomain of the same domain counts as the same site, so a page on another of my subdomains could still
 * post a plain form here with my cookie. Browsers say where a request comes from: {@code Sec-Fetch-Site}
 * ("same-origin" for the app itself), or on older browsers {@code Origin}. Requests without either aren't from a
 * browser page (curl, scripts), so they carry no one's cookie by accident and are let through. API tokens on
 * /api/v1 don't use the cookie, so they're not affected.
 */
public class SameOriginFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return SAFE.contains(req.getMethod()) || !uri.startsWith("/api/") || uri.startsWith(ApiTokenFilter.API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (allowed(req.getHeader("Sec-Fetch-Site"), req.getHeader("Origin"), req.getHeader("Host"))) {
            chain.doFilter(req, res);
            return;
        }
        AuthLog.crossSiteRefused(req);
        res.setStatus(403);
        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"Refused: this request came from another site.\"}");
    }

    /**
     * @param fetchSite the browser's Sec-Fetch-Site, if sent
     * @param origin    the browser's Origin, if sent
     * @param host      the Host the request was sent to (the proxy passes it on unchanged)
     */
    static boolean allowed(String fetchSite, String origin, String host) {
        if (fetchSite != null) return fetchSite.equals("same-origin") || fetchSite.equals("none"); // none: typed by me
        if (origin == null) return true; // not a browser page
        if (origin.equals("null") || host == null) return false;
        try {
            return host.equalsIgnoreCase(URI.create(origin).getRawAuthority());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
