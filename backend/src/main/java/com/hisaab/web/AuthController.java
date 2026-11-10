package com.hisaab.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthenticationManager auth;
    private final SecurityContextRepository contexts;
    private final com.hisaab.config.AppProperties props;
    private final org.springframework.beans.factory.ObjectProvider<com.hisaab.config.OidcSupport.LazyRegistrations> oidc;
    private final com.hisaab.config.OidcLinkService links;

    public AuthController(AuthenticationManager auth, SecurityContextRepository contexts, com.hisaab.config.AppProperties props,
                          org.springframework.beans.factory.ObjectProvider<com.hisaab.config.OidcSupport.LazyRegistrations> oidc,
                          com.hisaab.config.OidcLinkService links) {
        this.links = links;
        this.auth = auth;
        this.contexts = contexts;
        this.props = props;
        this.oidc = oidc;
    }

    public record OidcOption(String name, String startUrl) {}

    /** What the sign-in screen should offer. */
    public record Options(boolean passwordLogin, OidcOption oidc, boolean autoRedirect) {}

    @GetMapping("/auth/options")
    public Options options() {
        boolean on = oidc.getIfAvailable() != null;
        return new Options(com.hisaab.config.SecurityConfig.passwordLoginEnabled(props),
                on ? new OidcOption(props.oidc().name(), "/api/auth/oidc") : null,
                on && !Boolean.FALSE.equals(props.oidc().autoRedirect()));
    }

    /** Starts OIDC sign-in, or comes back with a readable error if the provider can't be reached. */
    @GetMapping("/auth/oidc")
    public void startOidc(HttpServletResponse res) throws java.io.IOException {
        var r = oidc.getIfAvailable();
        if (r == null) {
            res.sendRedirect("/?login_error=" + java.net.URLEncoder.encode("Sign-in with an identity provider isn't set up.", java.nio.charset.StandardCharsets.UTF_8));
        } else if (!r.reachable()) {
            res.sendRedirect("/?login_error=" + java.net.URLEncoder.encode(props.oidc().name() + " can't be reached right now.", java.nio.charset.StandardCharsets.UTF_8));
        } else {
            res.sendRedirect("/oauth2/authorization/" + com.hisaab.config.OidcSupport.REGISTRATION_ID);
        }
    }

    public record Login(String username, String password) {}

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody Login body, HttpServletRequest req, HttpServletResponse res) {
        if (!com.hisaab.config.SecurityConfig.passwordLoginEnabled(props)) {
            com.hisaab.config.AuthLog.signInFailed(req, body.username(), "password (turned off)");
            return ResponseEntity.status(403).body(Map.of("error", "Password sign-in is turned off. Use " + props.oidc().name() + "."));
        }
        try {
            Authentication a = auth.authenticate(new UsernamePasswordAuthenticationToken(body.username(), body.password()));
            if (req.getSession(false) != null) req.changeSessionId(); // prevent session fixation
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(a);
            SecurityContextHolder.setContext(ctx);
            contexts.saveContext(ctx, req, res);
            com.hisaab.config.AuthLog.signIn(req, a.getName());
            return ResponseEntity.ok(Map.of("username", a.getName()));
        } catch (AuthenticationException e) {
            com.hisaab.config.AuthLog.signInFailed(req, body.username());
            return ResponseEntity.status(401).body(Map.of("error", "Wrong username or password"));
        }
    }

    // ---- linking a first-time OIDC sign-in to this app's account ----------------------------------------------

    public record PendingLink(String email, String name, String provider, String username, boolean passwordRequired) {}

    /** The provider account waiting to be linked, if the last OIDC sign-in stopped to ask. */
    @GetMapping("/auth/link")
    public ResponseEntity<?> pendingLink(HttpServletRequest req) {
        var p = pending(req);
        if (p == null) return ResponseEntity.status(404).body(Map.of("error", "Nothing is waiting to be linked"));
        return ResponseEntity.ok(new PendingLink(p.email(), p.name(), props.oidc().name(), props.auth().username(),
                com.hisaab.config.SecurityConfig.passwordLoginEnabled(props)));
    }

    public record LinkIn(String password) {}

    /** Links the waiting provider account and signs in. Needs the app password while password sign-in is on. */
    @PostMapping("/auth/link")
    public ResponseEntity<?> confirmLink(@RequestBody(required = false) LinkIn in, HttpServletRequest req, HttpServletResponse res) {
        var p = pending(req);
        if (p == null) return ResponseEntity.status(400).body(Map.of("error", "Nothing is waiting to be linked. Sign in with " + props.oidc().name() + " again."));
        if (links.linkedId() != null) return ResponseEntity.status(409).body(Map.of("error", "Another account is already linked."));
        if (com.hisaab.config.SecurityConfig.passwordLoginEnabled(props)) {
            try {
                auth.authenticate(new UsernamePasswordAuthenticationToken(props.auth().username(), in == null ? null : in.password()));
            } catch (AuthenticationException e) {
                com.hisaab.config.AuthLog.signInFailed(req, p.email(), props.oidc().name() + ": link refused, wrong app password");
                return ResponseEntity.status(401).body(Map.of("error", "That's not the app's password"));
            }
        }
        links.save(p);
        req.getSession().removeAttribute(com.hisaab.config.OidcLinkService.PENDING);
        String name = p.email() != null ? p.email() : p.id();
        Authentication a = new UsernamePasswordAuthenticationToken(name, null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OWNER")));
        req.changeSessionId();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(a);
        SecurityContextHolder.setContext(ctx);
        contexts.saveContext(ctx, req, res);
        com.hisaab.config.AuthLog.signIn(req, name, props.oidc().name() + " (just linked)");
        return ResponseEntity.ok(Map.of("username", name));
    }

    @PostMapping("/auth/link/cancel")
    public void cancelLink(HttpServletRequest req) {
        var s = req.getSession(false);
        if (s != null) s.removeAttribute(com.hisaab.config.OidcLinkService.PENDING);
    }

    /** For Settings: which provider account is linked. */
    @GetMapping("/oidc-link")
    public com.hisaab.config.OidcLinkService.Link linkStatus() {
        return links.link();
    }

    @DeleteMapping("/oidc-link")
    public com.hisaab.config.OidcLinkService.Link unlink(HttpServletRequest req, Authentication who) {
        links.unlink();
        org.slf4j.LoggerFactory.getLogger("com.hisaab.security").info("OIDC account unlinked by user=\"{}\" ip={}", who.getName(), req.getRemoteAddr());
        return links.link();
    }

    private com.hisaab.config.OidcLinkService.Pending pending(HttpServletRequest req) {
        var s = req.getSession(false);
        return s == null ? null : (com.hisaab.config.OidcLinkService.Pending) s.getAttribute(com.hisaab.config.OidcLinkService.PENDING);
    }

    @GetMapping("/auth/me")
    public Map<String, String> me(Authentication a) {
        return Map.of("username", a.getName());
    }

    @org.springframework.beans.factory.annotation.Value("${APP_VERSION:dev}")
    private String version;

    /** Liveness, the running version (the image tag it was built from, or "dev") and the time zone (TZ) the app counts days in. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "version", version, "timeZone", com.hisaab.service.Money.ZONE.getId());
    }
}
