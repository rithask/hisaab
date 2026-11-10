package com.hisaab.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.HashSet;
import java.util.Set;

/**
 * OpenID Connect sign-in (e.g. Pocket ID), done the standard way: the provider is discovered from its issuer URL,
 * the authorization-code flow uses PKCE, and the user is identified by issuer + subject. Who may sign in is the
 * provider's decision; the app links one account on first sign-in (see SecurityConfig and OidcLinkService).
 */
public final class OidcSupport {
    public static final String REGISTRATION_ID = "oidc";
    private static final Logger log = LoggerFactory.getLogger(OidcSupport.class);

    private OidcSupport() {}

    /**
     * Looks the provider up (/.well-known/openid-configuration) on first use rather than at startup, so the app
     * still starts while the provider is down, and retries on the next sign-in if discovery failed.
     */
    public static final class LazyRegistrations implements ClientRegistrationRepository {
        private final AppProperties.Oidc cfg;
        private volatile ClientRegistration registration;

        public LazyRegistrations(AppProperties.Oidc cfg) {
            this.cfg = cfg;
        }

        @Override
        public ClientRegistration findByRegistrationId(String id) {
            if (!REGISTRATION_ID.equals(id)) return null;
            ClientRegistration r = registration;
            if (r != null) return r;
            synchronized (this) {
                if (registration == null) {
                    registration = ClientRegistrations.fromIssuerLocation(cfg.issuerUri().trim())
                            .registrationId(REGISTRATION_ID)
                            .clientId(cfg.clientId().trim())
                            .clientSecret(cfg.clientSecret() == null ? null : cfg.clientSecret().trim())
                            .clientName(cfg.name())
                            .scope("openid", "profile", "email")
                            // PKCE for the code exchange, as recommended for every client (RFC 9700), not just public ones
                            .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                            .userNameAttributeName("sub")
                            .build();
                    log.info("OIDC provider {} discovered at {}", cfg.name(), cfg.issuerUri());
                }
                return registration;
            }
        }

        /** True when the provider answers discovery; used to fail gracefully before redirecting. */
        public boolean reachable() {
            try {
                return findByRegistrationId(REGISTRATION_ID) != null;
            } catch (RuntimeException e) {
                log.warn("OIDC provider {} unreachable at {}: {}", cfg.name(), cfg.issuerUri(), e.toString());
                return false;
            }
        }
    }

    /** Loads the OIDC user and gives them the owner role; whether it's the linked account is checked after sign-in. */
    public static OAuth2UserService<OidcUserRequest, OidcUser> userService() {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser user = delegate.loadUser(request);
            Set<GrantedAuthority> authorities = new HashSet<>(user.getAuthorities());
            authorities.add(new SimpleGrantedAuthority("ROLE_OWNER"));
            // the name shown in logs and the UI; identity itself is always issuer + subject
            String nameKey = user.getEmail() != null ? "email" : user.getPreferredUsername() != null ? "preferred_username" : "sub";
            return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), nameKey);
        };
    }
}
