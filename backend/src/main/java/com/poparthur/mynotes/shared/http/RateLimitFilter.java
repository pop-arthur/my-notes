package com.poparthur.mynotes.shared.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Installed only in the SecurityFilterChain, not as a second servlet filter. */
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimitService limits;
    private final ProblemWriter problems;

    public RateLimitFilter(RateLimitService limits, ProblemWriter problems) {
        this.limits = limits;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String group = null;
        if ("POST".equals(request.getMethod())) {
            group = switch (path) {
                case "/api/auth/register", "/api/auth/resend-verification", "/api/auth/forgot-password" -> "mail";
                case "/api/auth/login" -> "login";
                case "/api/auth/verify-email", "/api/auth/reset-password" -> "token";
                default -> null;
            };
        } else if ("GET".equals(request.getMethod())) {
            group = switch (path) {
                case "/api/auth/csrf" -> "csrf";
                case "/oauth2/authorization/google" -> "google";
                default -> null;
            };
        }
        if (group != null) {
            long retry = limits.acquire(path, request.getRemoteAddr(), group);
            if (retry > 0) {
                response.setHeader("Retry-After", Long.toString(retry));
                problems.write(request, response, 429, "rate_limited");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
