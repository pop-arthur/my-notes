package com.poparthur.mynotes.identity.http.security;

import com.poparthur.mynotes.identity.service.CurrentUserService;
import com.poparthur.mynotes.identity.service.result.SessionIdentity;
import com.poparthur.mynotes.shared.http.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionVersionFilter extends OncePerRequestFilter {
    private final CurrentUserService users;
    private final SessionSupport sessions;
    private final ProblemWriter problems;

    public SessionVersionFilter(CurrentUserService users, SessionSupport sessions, ProblemWriter problems) {
        this.users = users; this.sessions = sessions; this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            boolean valid;
            try {
                valid = authentication.getPrincipal() instanceof SessionIdentity identity
                        && users.isSessionValid(identity.userId(), identity.authVersion());
            } catch (RuntimeException failure) {
                problems.write(request, response, 500, "internal_error");
                return;
            }
            if (!valid) {
                sessions.logout(request, response);
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if (path.startsWith("/api/tasks") || path.equals("/api/auth/me") || path.equals("/api/auth/logout")) {
                    problems.write(request, response, 401, "unauthenticated");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
