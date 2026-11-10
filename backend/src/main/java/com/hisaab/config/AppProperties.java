package com.hisaab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(Auth auth, Mail mail, Storage storage, Prices prices, Oidc oidc) {

    /** @param passwordLogin whether the username/password form is offered (only honoured when OIDC is set up) */
    public record Auth(String username, String password, Boolean passwordLogin) {
        public boolean passwordLoginRequested() {
            return passwordLogin == null || passwordLogin;
        }
    }

    /**
     * Sign-in with an OpenID Connect provider such as Pocket ID.
     *
     * Who may sign in is decided at the provider (restrict the client to your user or group there); the app then
     * links exactly one account by issuer + subject, the identifier OIDC guarantees is stable.
     *
     * @param providerName shown on the sign-in button only
     * @param autoRedirect send the sign-in page straight to the provider
     */
    public record Oidc(String issuerUri, String clientId, String clientSecret, String providerName, Boolean autoRedirect) {
        public boolean configured() {
            return issuerUri != null && !issuerUri.isBlank() && clientId != null && !clientId.isBlank();
        }

        public String name() {
            return providerName == null || providerName.isBlank() ? "Pocket ID" : providerName.trim();
        }

    }

    public record Mail(String host, int port, String username, String password, String folder) {
        public boolean configured() {
            return host != null && !host.isBlank() && username != null && !username.isBlank()
                    && password != null && !password.isBlank();
        }
    }

    public record Storage(String dir) {}

    public record Prices(boolean enabled) {}
}
