package com.example.moderncms;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    @GetMapping("/")
    public String home(@AuthenticationPrincipal OidcUser user) {
        Object acr = user.getClaims().getOrDefault("acr", "(none)");
        Object amr = user.getClaims().getOrDefault("amr", "(none)");
        return """
                <h1>Modern CMS (GCP)</h1>
                <p>Logged in as: <b>%s</b></p>
                <p>acr claim (auth context / step-up indicator): <b>%s</b></p>
                <p>amr claim: <b>%s</b></p>
                <a href="/logout">Logout</a>
                """.formatted(user.getPreferredUsername(), acr, amr);
    }
}
