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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationService {
    private final UserRepository users;
    private final PasswordCredentialRepository credentials;
    private final EmailTokenRepository tokenRepository;
    private final EmailTokenService tokens;
    private final IdentityMailDelivery mail;
    private final InputValidation validation;
    private final ServiceTime time;
    private final NewTransaction transaction;

    public EmailVerificationService(UserRepository users, PasswordCredentialRepository credentials,
                                    EmailTokenRepository tokenRepository, EmailTokenService tokens,
                                    IdentityMailDelivery mail, InputValidation validation,
                                    ServiceTime time, NewTransaction transaction) {
        this.users = users;
        this.credentials = credentials;
        this.tokenRepository = tokenRepository;
        this.tokens = tokens;
        this.mail = mail;
        this.validation = validation;
        this.time = time;
        this.transaction = transaction;
    }

    @Transactional
    public void verify(String rawToken) {
        var token = tokens.consume(rawToken, TokenPurpose.EMAIL_VERIFICATION);
        if (!users.confirmEmail(token.getUserId(), time.now())) {
            throw new InvalidTokenException();
        }
        tokenRepository.revokeByUserIdAndPurpose(token.getUserId(), TokenPurpose.EMAIL_VERIFICATION, time.now());
    }

    public void resend(String email) {
        var sender = mail.requireSender();
        String normalized = validation.email(email);
        var message = transaction.execute(() -> {
            var user = users.findByEmail(normalized).orElse(null);
            if (user == null) {
                return null;
            }
            user = users.findByIdForUpdate(user.getId()).orElseThrow(InvalidTokenException::new);
            if (user.isEmailVerified() || credentials.findByUserId(user.getId()).isEmpty()) {
                return null;
            }
            return tokens.issue(user.getId(), TokenPurpose.EMAIL_VERIFICATION);
        });
        mail.send(sender, message);
    }
}
