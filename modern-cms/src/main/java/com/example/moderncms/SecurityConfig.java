package com.example.moderncms;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Unlike member-cms/payment-cms, this app can be modified directly, so it talks OIDC
 * to Keycloak itself instead of sitting behind a proxy.
 *
 * Logout is wired to Keycloak's end_session_endpoint (RP-initiated logout) AND to
 * receive real back-channel logout pushes, since Spring Security 6.2+ supports this
 * natively for OIDC clients (unlike oauth2-proxy, where we deliberately rely on the
 * short Access Token Lifespan instead of a push mechanism).
 *
 * PKCE isn't sent automatically for confidential clients (only public ones), so it's
 * wired in explicitly here to match the Keycloak client's pkce.code.challenge.method=S256
 * requirement - defense-in-depth against authorization code interception even though this
 * client also has a client_secret.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2Login(oauth2 -> oauth2
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
            )
            .oidcLogout(oidcLogout -> oidcLogout
                .backChannel(Customizer.withDefaults())
            )
            .logout(logout -> logout
                .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository))
            );

        return http.build();
    }

    private OidcClientInitiatedLogoutSuccessHandler oidcLogoutSuccessHandler(
            ClientRegistrationRepository clientRegistrationRepository) {
        OidcClientInitiatedLogoutSuccessHandler handler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri("{baseUrl}");
        return handler;
    }
}
