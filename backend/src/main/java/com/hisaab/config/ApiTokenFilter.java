package com.hisaab.config;

import com.hisaab.domain.ApiToken;
import com.hisaab.domain.ApiToken.Job;
import com.hisaab.features.FeatureService;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticates API tokens ({@code Authorization: Bearer hisaab_...}, RFC 6750) on the API for other apps and
 * shortcuts ({@code /api/v1/**}). A token only gets through to the endpoints of its one job, for this request
 * only: no session is created. Requests are rate limited per client, and repeated bad tokens lock the client out
 * for a while. Refusals are problem details (RFC 9457), with {@code WWW-Authenticate} on 401 and 403 (RFC 6750)
 * and {@code Retry-After} on 429 (RFC 6585).
 */
public class ApiTokenFilter extends OncePerRequestFilter {
    public static final String API_PREFIX = "/api/v1/";
    /** Request attribute holding the {@link ApiToken} that authenticated a Data API request. */
    public static final String TOKEN = "hisaab.apiToken";

    private static final int MAX_PER_MINUTE = 60;
    private static final int MAX_FAILURES = 10;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);

    private final ApiTokenService tokens;
    private final FeatureService features;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public ApiTokenFilter(ApiTokenService tokens, FeatureService features) {
        this.tokens = tokens;
        this.features = features;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(API_PREFIX);
    }

    /** The jobs whose tokens may call this endpoint (empty: no such endpoint). */
    static Set<Job> jobsFor(String method, String path) {
        if ("POST".equals(method)) {
            return switch (path) {
                case "/api/v1/sms" -> Set.of(Job.SEND_SMS);
                case "/api/v1/transactions" -> Set.of(Job.ADD_TRANSACTIONS);
                default -> Set.of();
            };
        }
        if (!"GET".equals(method)) return Set.of();
        return switch (path) {
            case "/api/v1/transactions" -> Set.of(Job.READ_TRANSACTIONS);
            case "/api/v1/balances" -> Set.of(Job.READ_BALANCES);
            case "/api/v1/summary" -> Set.of(Job.READ_TOTALS);
            case "/api/v1/categories" -> Set.of(Job.READ_TRANSACTIONS, Job.ADD_TRANSACTIONS, Job.READ_TOTALS);
            case "/api/v1/accounts" -> Set.of(Job.READ_TRANSACTIONS, Job.ADD_TRANSACTIONS, Job.READ_TOTALS, Job.READ_BALANCES);
            default -> path.matches("/api/v1/transactions/[^/]+") ? Set.of(Job.READ_TRANSACTIONS) : Set.of();
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String client = req.getRemoteAddr();
        Instant now = Instant.now();
        if (count(failures, client, now, FAILURE_WINDOW) >= MAX_FAILURES) {
            res.setHeader("Retry-After", Long.toString(FAILURE_WINDOW.toSeconds()));
            reject(req, res, 429, "Too Many Requests", "Too many invalid tokens. Try again later.");
            return;
        }
        if (record(hits, client, now, Duration.ofMinutes(1)) > MAX_PER_MINUTE) {
            res.setHeader("Retry-After", "60");
            reject(req, res, 429, "Too Many Requests", "Too many requests. Slow down.");
            return;
        }

        String presented = presentedToken(req);
        if (presented == null) {
            res.setHeader("WWW-Authenticate", "Bearer realm=\"hisaab\"");
            reject(req, res, 401, "Unauthorized", "Send an API token as Authorization: Bearer <token>.");
            return;
        }
        ApiToken token = tokens.authenticate(presented).orElse(null);
        if (token == null) {
            int n = record(failures, client, now, FAILURE_WINDOW);
            AuthLog.tokenRejected(req, n);
            if (n == MAX_FAILURES) AuthLog.tokenLockedOut(req);
            res.setHeader("WWW-Authenticate", "Bearer realm=\"hisaab\", error=\"invalid_token\"");
            reject(req, res, 401, "Unauthorized", "Invalid API token");
            return;
        }
        Set<Job> allowed = jobsFor(req.getMethod(), req.getRequestURI());
        if (allowed.isEmpty()) {
            reject(req, res, 404, "Not Found", "There's no such endpoint. See docs/api.md.");
            return;
        }
        if (!allowed.contains(token.getJob())) {
            AuthLog.tokenUsed(req, token.getName(), 403);
            res.setHeader("WWW-Authenticate", "Bearer realm=\"hisaab\", error=\"insufficient_scope\"");
            reject(req, res, 403, "Forbidden", "This token can only " + does(token.getJob()) + ".");
            return;
        }
        // SMS forwarding is part of the app itself; everything else is the optional Data API
        if (token.getJob() != Job.SEND_SMS && !features.on(FeatureService.Feature.DATA_API)) {
            AuthLog.tokenUsed(req, token.getName(), 403);
            reject(req, res, 403, "Forbidden", "The Data API is turned off in Settings.");
            return;
        }

        var auth = new UsernamePasswordAuthenticationToken("token:" + token.getId(), null,
                List.of(new SimpleGrantedAuthority("ROLE_API")));
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        req.setAttribute(TOKEN, token);
        try {
            chain.doFilter(req, res);
        } finally {
            AuthLog.tokenUsed(req, token.getName(), res.getStatus());
        }
    }

    /** What a job lets a token do, for messages: "This token can only …". */
    public static String does(Job job) {
        return switch (job) {
            case SEND_SMS -> "send SMS";
            case READ_TRANSACTIONS -> "read transactions";
            case ADD_TRANSACTIONS -> "add transactions";
            case READ_BALANCES -> "read balances";
            case READ_TOTALS -> "read totals";
        };
    }

    private static String presentedToken(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        return h != null && h.regionMatches(true, 0, "Bearer ", 0, 7) && h.length() > 7 ? h.substring(7).trim() : null;
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

    private static void reject(HttpServletRequest req, HttpServletResponse res, int status, String title, String detail) throws IOException {
        res.setStatus(status);
        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/problem+json");
        // same fields as the controllers' problem details; no "type" means about:blank (RFC 9457 §3.1.1)
        res.getWriter().write("{\"title\":" + json(title) + ",\"status\":" + status + ",\"detail\":" + json(detail)
                + ",\"instance\":" + json(req.getRequestURI()) + "}");
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
