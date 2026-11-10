package com.hisaab.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Cache headers for the web app. Built files under /assets have the content hash in their names, so they can be
 * cached for good. Everything else (the page, the service worker, the manifest, icons) is checked on every load,
 * so a new version shows up straight away. /api keeps Spring Security's no-store.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CacheHeaders extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String p = req.getRequestURI();
        if (!p.startsWith("/api/") && !p.startsWith("/oauth2/") && !p.startsWith("/login/")) {
            res.setHeader("Cache-Control", p.startsWith("/assets/") ? "public, max-age=31536000, immutable" : "no-cache");
        }
        chain.doFilter(req, res);
    }
}
