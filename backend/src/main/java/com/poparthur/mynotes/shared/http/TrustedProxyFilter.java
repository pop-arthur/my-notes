package com.poparthur.mynotes.shared.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Dedicated edge trust, never general Forwarded/X-Forwarded-For trust. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TrustedProxyFilter extends OncePerRequestFilter {
    public static final String SECRET_HEADER = "X-My-Notes-Proxy-Secret";
    public static final String CLIENT_HEADER = "X-My-Notes-Client-IP";
    private final byte[] secret;
    private final ProblemWriter problems;

    public TrustedProxyFilter(@Value("${app.proxy.secret:}") String secret, ProblemWriter problems) {
        if (!secret.isEmpty() && (secret.length() < 32 || !secret.equals(secret.strip()))) {
            throw new IllegalStateException("Proxy secret must contain at least 32 characters without outer whitespace");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The public, status-only healthcheck works even when the tunnel is unavailable.
        if (secret.length == 0 || ("GET".equals(request.getMethod())
                && "/actuator/health".equals(request.getRequestURI()))) {
            chain.doFilter(request, response);
            return;
        }
        String supplied = singleHeader(request, SECRET_HEADER);
        String client = numericAddress(singleHeader(request, CLIENT_HEADER));
        if (supplied == null || !MessageDigest.isEqual(secret, supplied.getBytes(StandardCharsets.UTF_8)) || client == null) {
            problems.write(request, response, 403, "access_denied");
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public String getRemoteAddr() { return client; }
            @Override public String getRemoteHost() { return client; }
        }, response);
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        return values.size() == 1 ? values.getFirst() : null;
    }

    private static String numericAddress(String value) {
        if (value == null || value.length() > 45) return null;
        try {
            if (value.contains(":")) {
                if (!value.matches("[0-9a-fA-F:.]+")) return null;
            } else {
                if (!value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return null;
                for (String part : value.split("\\.")) if (Integer.parseInt(part) > 255) return null;
            }
            return InetAddress.getByName(value).getHostAddress();
        } catch (Exception invalidAddress) {
            return null;
        }
    }

}
