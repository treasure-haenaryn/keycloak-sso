package com.example.membercms;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    @GetMapping("/")
    public String home(Authentication authentication) {
        return """
                <h1>Member CMS</h1>
                <p>Logged in as: <b>%s</b></p>
                <p>(This username came from the X-Forwarded-Email header injected by oauth2-proxy)</p>
                <a href="/logout">Logout</a>
                """.formatted(authentication.getName());
    }

    @GetMapping("/whoami")
    public String whoami(Authentication authentication) {
        return "{\"username\": \"%s\"}".formatted(authentication.getName());
    }
}
