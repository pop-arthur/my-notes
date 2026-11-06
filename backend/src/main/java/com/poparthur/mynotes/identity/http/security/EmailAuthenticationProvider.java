package com.poparthur.mynotes.identity.http.security;

import com.poparthur.mynotes.identity.service.PasswordAuthenticationService;
import com.poparthur.mynotes.identity.service.exception.InvalidCredentialsException;
import com.poparthur.mynotes.identity.service.result.SessionIdentity;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

@Component
public class EmailAuthenticationProvider implements AuthenticationProvider {
    private final PasswordAuthenticationService passwords;

    public EmailAuthenticationProvider(PasswordAuthenticationService passwords) { this.passwords = passwords; }

    @Override
    public Authentication authenticate(Authentication input) {
        try {
            var result = passwords.authenticate(input.getName(), (String) input.getCredentials());
            return UsernamePasswordAuthenticationToken.authenticated(
                    new SessionIdentity(result.userId(), result.authVersion()), null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));
        } catch (InvalidCredentialsException failure) {
            throw new BadCredentialsException("Invalid credentials");
        }
    }

    @Override
    public boolean supports(Class<?> type) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
    }
}
