package com.example.paymentcms;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.AuthenticationUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.preauth.RequestHeaderAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.List;

/**
 * Deliberately near-identical to member-cms's SecurityConfig, not shared as a library:
 * these represent two independently-owned legacy systems in real life.
 *
 * No form login here on purpose: this app is only ever meant to be reached through
 * oauth2-proxy (network isolation enforces that, and the proxy also gates group
 * membership before ever forwarding here). Identity comes pre-verified via a trusted
 * header; if it's missing, we fail closed with 401.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // See member-cms's SecurityConfig for why this is X-Forwarded-Email, not X-Forwarded-User.
    private static final String TRUSTED_HEADER = "X-Forwarded-Email";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AuthenticationManager authenticationManager) throws Exception {
        RequestHeaderAuthenticationFilter headerFilter = new RequestHeaderAuthenticationFilter();
        headerFilter.setPrincipalRequestHeader(TRUSTED_HEADER);
        headerFilter.setExceptionIfHeaderMissing(false);
        headerFilter.setAuthenticationManager(authenticationManager);

        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .addFilterBefore(headerFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                (request, response, authException) ->
                    response.sendError(401, "No trusted identity header present (bypassed the proxy?)")
            ))
            .logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "GET"))
                .logoutSuccessUrl("/oauth2/sign_out")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            );

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationUserDetailsService<PreAuthenticatedAuthenticationToken> preAuthUserDetailsService) {
        PreAuthenticatedAuthenticationProvider provider = new PreAuthenticatedAuthenticationProvider();
        provider.setPreAuthenticatedUserDetailsService(preAuthUserDetailsService);
        return new ProviderManager(provider);
    }

    @Bean
    public AuthenticationUserDetailsService<PreAuthenticatedAuthenticationToken> preAuthUserDetailsService() {
        return token -> {
            String username = (String) token.getPrincipal();
            return new User(username, "N/A", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        };
    }
}
