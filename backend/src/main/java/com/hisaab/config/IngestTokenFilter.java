package com.hisaab.config;

import com.hisaab.service.ApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticates the SMS ingest endpoint with {@code Authorization: Bearer hisaab_...} (or {@code X-Api-Key}).
 * A valid token grants ROLE_INGEST for this one request only: no session is created and no other endpoint
 * accepts that role. Requests are rate limited per client, and repeated bad tokens lock the client out for a while.
 */
public class IngestTokenFilter extends OncePerRequestFilter {
    public static final String PATH = "/api/ingest/sms";

    private static final int MAX_PER_MINUTE = 30;
    private static final int MAX_FAILURES = 10;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);

    private final ApiTokenService tokens;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public IngestTokenFilter(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String client = req.getRemoteAddr();
        Instant now = Instant.now();
        if (count(failures, client, now, FAILURE_WINDOW) >= MAX_FAILURES) {
            reject(res, 429, "Too many invalid tokens. Try again later.");
            return;
        }
        if (record(hits, client, now, Duration.ofMinutes(1)) > MAX_PER_MINUTE) {
            reject(res, 429, "Too many requests. Slow down.");
            return;
        }

        String presented = presentedToken(req);
        if (presented != null) {
            if (!tokens.verify(presented)) {
                int n = record(failures, client, now, FAILURE_WINDOW);
                AuthLog.tokenRejected(req, n);
                if (n == MAX_FAILURES) AuthLog.tokenLockedOut(req);
                reject(res, 401, "Invalid API token");
                return;
            }
            AuthLog.tokenUsed(req);
            var auth = new UsernamePasswordAuthenticationToken("sms-ingest", null,
                    List.of(new SimpleGrantedAuthority("ROLE_INGEST")));
            var ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);
        }
        // No token: fall through, a logged-in browser session may still be allowed (pasting an SMS in the app).
        chain.doFilter(req, res);
    }

    private static String presentedToken(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) return h.substring(7).trim();
        String k = req.getHeader("X-Api-Key");
        return k == null || k.isBlank() ? null : k.trim();
    }

    private static int record(Map<String, Deque<Instant>> map, String key, Instant now, Duration window) {
        Deque<Instant> q = map.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now, window);
            q.addLast(now);
            return q.size();
        }
    }

    private static int count(Map<String, Deque<Instant>> map, String key, Instant now, Duration window) {
        Deque<Instant> q = map.get(key);
        if (q == null) return 0;
        synchronized (q) {
            prune(q, now, window);
            return q.size();
        }
    }

    private static void prune(Deque<Instant> q, Instant now, Duration window) {
        while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(window))) q.removeFirst();
    }

    private static void reject(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
