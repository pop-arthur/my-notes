package com.poparthur.mynotes.identity.service;

import com.poparthur.mynotes.identity.repo.PasswordCredentialRepository;
import com.poparthur.mynotes.identity.repo.UserRepository;
import com.poparthur.mynotes.identity.service.exception.EmailNotVerifiedException;
import com.poparthur.mynotes.identity.service.exception.InvalidCredentialsException;
import com.poparthur.mynotes.identity.service.result.AuthenticationResult;
import com.poparthur.mynotes.shared.service.InputValidation;
import com.poparthur.mynotes.shared.service.UuidGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PasswordAuthenticationService {
    private final UserRepository users;
    private final PasswordCredentialRepository credentials;
    private final InputValidation validation;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    public PasswordAuthenticationService(UserRepository users, PasswordCredentialRepository credentials,
                                         InputValidation validation, PasswordEncoder passwords, UuidGenerator ids) {
        this.users = users;
        this.credentials = credentials;
        this.validation = validation;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(ids.next().toString());
    }

    public AuthenticationResult authenticate(String email, String password) {
        String normalized = validation.email(email);
        validation.loginPassword(password);
        var user = users.findByEmail(normalized).orElse(null);
        var credential = user == null ? null : credentials.findByUserId(user.getId()).orElse(null);
        boolean matches = passwords.matches(password, credential == null ? dummyHash : credential.getPasswordHash());
        if (user == null || credential == null || !matches) {
            throw new InvalidCredentialsException();
        }
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }
        return IdentityResults.authentication(user);
    }
}
