package com.poparthur.mynotes.identity.http;

import com.poparthur.mynotes.shared.http.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "app.oauth.google.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledGoogleController {
    private final ProblemWriter problems;
    public DisabledGoogleController(ProblemWriter problems) { this.problems = problems; }

    @GetMapping("/oauth2/authorization/google")
    public void start(HttpServletRequest request, HttpServletResponse response) throws IOException {
        problems.write(request, response, 503, "google_unavailable");
    }

    @GetMapping("/login/oauth2/code/google")
    public void callback(HttpServletResponse response) throws IOException {
        response.setStatus(302);
        response.setHeader("Location", "/login?error=oauth_failed");
    }
}
