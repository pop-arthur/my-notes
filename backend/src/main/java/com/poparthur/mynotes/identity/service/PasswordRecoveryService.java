package com.poparthur.mynotes.identity.service;

import com.poparthur.mynotes.identity.repo.EmailTokenRepository;
import com.poparthur.mynotes.identity.repo.PasswordCredentialRepository;
import com.poparthur.mynotes.identity.repo.UserRepository;
import com.poparthur.mynotes.identity.repo.entity.TokenPurpose;
import com.poparthur.mynotes.identity.service.exception.InvalidTokenException;
import com.poparthur.mynotes.identity.service.mail.IdentityMailDelivery;
import com.poparthur.mynotes.shared.service.InputValidation;
import com.poparthur.mynotes.shared.service.NewTransaction;
import com.poparthur.mynotes.shared.service.ServiceTime;
import com.poparthur.mynotes.identity.service.event.PasswordResetCompleted;
import com.poparthur.mynotes.identity.service.result.PasswordResetResult;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordRecoveryService {
    private final ApplicationEventPublisher events;
    private final UserRepository users;
    private final PasswordCredentialRepository credentials;
    private final EmailTokenRepository tokenRepository;
    private final EmailTokenService tokens;
    private final IdentityMailDelivery mail;
    private final InputValidation validation;
    private final PasswordEncoder passwords;
    private final ServiceTime time;
    private final NewTransaction transaction;

    public PasswordRecoveryService(UserRepository users, PasswordCredentialRepository credentials,
                                   EmailTokenRepository tokenRepository, EmailTokenService tokens,
                                   IdentityMailDelivery mail, InputValidation validation, PasswordEncoder passwords,
                                   ServiceTime time, NewTransaction transaction, ApplicationEventPublisher events) {
        this.events = events;
        this.users = users;
        this.credentials = credentials;
        this.tokenRepository = tokenRepository;
        this.tokens = tokens;
        this.mail = mail;
        this.validation = validation;
        this.passwords = passwords;
        this.time = time;
        this.transaction = transaction;
    }

    public void requestReset(String email) {
        var sender = mail.requireSender();
        String normalized = validation.email(email);
        var message = transaction.execute(() -> {
            var user = users.findByEmail(normalized).orElse(null);
            if (user == null) {
                return null;
            }
            user = users.findByIdForUpdate(user.getId()).orElseThrow(InvalidTokenException::new);
            if (!user.isEmailVerified() || credentials.findByUserId(user.getId()).isEmpty()) {
                return null;
            }
            return tokens.issue(user.getId(), TokenPurpose.PASSWORD_RESET);
        });
        mail.send(sender, message);
    }

    @Transactional
    public PasswordResetResult reset(String rawToken, String newPassword) {
        validation.newPassword(newPassword, "newPassword");
        String hash = passwords.encode(newPassword);
        var token = tokens.consume(rawToken, TokenPurpose.PASSWORD_RESET);
        var user = users.findById(token.getUserId()).orElseThrow(InvalidTokenException::new);
        var now = time.now();
        if (!user.isEmailVerified() || !credentials.updatePasswordHash(user.getId(), hash, now)) {
            throw new InvalidTokenException();
        }
        tokenRepository.revokeByUserIdAndPurpose(user.getId(), TokenPurpose.PASSWORD_RESET, now);
        if (!users.incrementAuthVersion(user.getId(), now)) {
            throw new InvalidTokenException();
        }
        long version = users.findById(user.getId()).orElseThrow(InvalidTokenException::new).getAuthVersion();
        events.publishEvent(new PasswordResetCompleted(user.getId(), version));
        return new PasswordResetResult(user.getId(), version);
    }
}
