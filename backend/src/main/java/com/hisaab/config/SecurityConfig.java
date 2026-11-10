package com.hisaab.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.hisaab.service.ApiTokenService;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Single-user, session-cookie auth, plus a bearer API token that only works for SMS ingest. CSRF protection relies on
 * the session cookie being SameSite=Lax (see application.yml): browsers don't send it on cross-site POST/PUT/DELETE,
 * and every state change here is one of those. The cookie is marked Secure automatically on HTTPS requests.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** Present only when OIDC is configured. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${app.oidc.issuer-uri:}' != '' and '${app.oidc.client-id:}' != ''")
    OidcSupport.LazyRegistrations oidcRegistrations(AppProperties props) {
        return new OidcSupport.LazyRegistrations(props.oidc());
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SecurityContextRepository repo, ApiTokenService tokens, AppProperties props,
                                    org.springframework.beans.factory.ObjectProvider<OidcSupport.LazyRegistrations> oidc,
                                    OidcLinkService links) throws Exception {
        OidcSupport.LazyRegistrations registrations = oidc.getIfAvailable();
        if (registrations != null) {
            http.oauth2Login(o -> o
                    .clientRegistrationRepository(registrations)
                    .loginPage("/")
                    .userInfoEndpoint(u -> u.oidcUserService(OidcSupport.userService()))
                    .successHandler((req, res, auth) -> {
                        var user = (org.springframework.security.oauth2.core.oidc.user.OidcUser) auth.getPrincipal();
                        String id = user.getIssuer() + "|" + user.getSubject();
                        String linked = links.linkedId();
                        if (id.equals(linked)) {
                            AuthLog.signIn(req, auth.getName(), props.oidc().name());
                            res.sendRedirect("/");
                            return;
                        }
                        // Not (yet) the linked account: undo the sign-in that was just saved to the session.
                        SecurityContextHolder.clearContext();
                        req.getSession().removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                        if (linked != null) {
                            String why = "A different " + props.oidc().name() + " account is linked to this app. Unlink it in Settings first.";
                            AuthLog.signInFailed(req, auth.getName(), props.oidc().name() + ": not the linked account");
                            res.sendRedirect("/?login_error=" + java.net.URLEncoder.encode(why, java.nio.charset.StandardCharsets.UTF_8));
                            return;
                        }
                        req.getSession().setAttribute(OidcLinkService.PENDING, new OidcLinkService.Pending(id, user.getEmail(), user.getFullName()));
                        AuthLog.signInFailed(req, auth.getName(), props.oidc().name() + ": first sign-in, waiting to be linked");
                        res.sendRedirect("/?link");
                    })
                    .failureHandler((req, res, ex) -> {
                        String why = ex instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException oe
                                && oe.getError().getDescription() != null ? oe.getError().getDescription() : ex.getMessage();
                        AuthLog.signInFailed(req, "-", props.oidc().name() + ": " + why);
                        res.sendRedirect("/?login_error=" + java.net.URLEncoder.encode(why, java.nio.charset.StandardCharsets.UTF_8));
                    }));
        }
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .securityContext(s -> s.securityContextRepository(repo))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/login", "/api/auth/options", "/api/auth/oidc", "/api/auth/link", "/api/auth/link/cancel", "/api/health").permitAll()
                        // The API token only ever unlocks SMS ingest; everything else needs the owner's session.
                        .requestMatchers(HttpMethod.POST, IngestTokenFilter.PATH).hasAnyRole("OWNER", "INGEST")
                        .requestMatchers("/api/**").hasRole("OWNER")
                        .anyRequest().permitAll())
                .addFilterBefore(new IngestTokenFilter(tokens), AnonymousAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .logout(l -> l.logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((req, res, auth) -> {
                            if (auth != null) AuthLog.signOut(req, auth.getName());
                            res.setStatus(200);
                        }));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(AppProperties props, PasswordEncoder encoder) {
        String password = props.auth().password();
        if (password == null || password.isBlank()) {
            if (passwordLoginEnabled(props)) throw new IllegalStateException("APP_PASSWORD must be set (or configure OIDC and set PASSWORD_LOGIN=false)");
            // Password sign-in is off: keep a random, unguessable password so the account can't be used that way.
            // (bcrypt accepts at most 72 bytes, so 32 random bytes as hex.)
            byte[] random = new byte[32];
            new java.security.SecureRandom().nextBytes(random);
            password = java.util.HexFormat.of().formatHex(random);
        }
        // Accept either a plain password or a pre-encoded one like {bcrypt}$2a$...
        String encoded = password.startsWith("{") ? password : encoder.encode(password);
        return new InMemoryUserDetailsManager(
                User.withUsername(props.auth().username()).password(encoded).roles("OWNER").build());
    }

    /** Password sign-in can only be turned off when OIDC is configured, so there's always a way in. */
    public static boolean passwordLoginEnabled(AppProperties props) {
        return props.auth().passwordLoginRequested() || props.oidc() == null || !props.oidc().configured();
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
}
