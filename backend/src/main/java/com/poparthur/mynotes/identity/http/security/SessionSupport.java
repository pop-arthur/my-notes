package com.poparthur.mynotes.identity.http.security;

import com.poparthur.mynotes.identity.service.result.SessionIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.stereotype.Component;

@Component
public class SessionSupport {
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy strategy;
    private final CsrfTokenRepository csrf;
    private final CookieSerializer cookies;

    public SessionSupport(SecurityContextRepository contexts, SessionAuthenticationStrategy strategy,
                           CsrfTokenRepository csrf, CookieSerializer cookies) {
        this.contexts = contexts;
        this.strategy = strategy;
        this.csrf = csrf;
        this.cookies = cookies;
    }

    public void establish(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        strategy.onAuthentication(authentication, request, response);
        var identity = (SessionIdentity) authentication.getPrincipal();
        request.getSession(true).setAttribute(SessionIdentity.ATTRIBUTE, identity);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    public void establish(SessionIdentity identity, HttpServletRequest request, HttpServletResponse response) {
        establish(UsernamePasswordAuthenticationToken.authenticated(identity, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))), request, response);
    }

    public void clearCsrf(HttpServletRequest request, HttpServletResponse response) {
        csrf.saveToken(null, request, response);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        var empty = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(empty);
        contexts.saveContext(empty, request, response);
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        var cookie = new CookieSerializer.CookieValue(request, response, "");
        cookie.setCookieMaxAge(0);
        cookies.writeCookieValue(cookie);
    }
}
