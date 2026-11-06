package com.poparthur.mynotes.identity.service;

import com.poparthur.mynotes.identity.repo.PasswordCredentialRepository;
import com.poparthur.mynotes.identity.repo.UserRepository;
import com.poparthur.mynotes.identity.repo.entity.PasswordCredentialEntity;
import com.poparthur.mynotes.identity.repo.entity.TokenPurpose;
import com.poparthur.mynotes.identity.repo.entity.UserEntity;
import com.poparthur.mynotes.identity.repo.exception.DuplicateUserException;
import com.poparthur.mynotes.identity.service.mail.IdentityMail;
import com.poparthur.mynotes.identity.service.mail.IdentityMailDelivery;
import com.poparthur.mynotes.shared.service.InputValidation;
import com.poparthur.mynotes.shared.service.NewTransaction;
import com.poparthur.mynotes.shared.service.ServiceTime;
import com.poparthur.mynotes.shared.service.UuidGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class RegistrationService {
    private final UserRepository users;
    private final PasswordCredentialRepository credentials;
    private final EmailTokenService tokens;
    private final IdentityMailDelivery mail;
    private final InputValidation validation;
    private final PasswordEncoder passwords;
    private final ServiceTime time;
    private final UuidGenerator ids;
    private final NewTransaction transaction;

    public RegistrationService(UserRepository users, PasswordCredentialRepository credentials,
                               EmailTokenService tokens, IdentityMailDelivery mail, InputValidation validation,
                               PasswordEncoder passwords, ServiceTime time, UuidGenerator ids, NewTransaction transaction) {
        this.users = users;
        this.credentials = credentials;
        this.tokens = tokens;
        this.mail = mail;
        this.validation = validation;
        this.passwords = passwords;
        this.time = time;
        this.ids = ids;
        this.transaction = transaction;
    }

    /** Neutral result for new and occupied email addresses. Does not authenticate the user. */
    public void register(String email, String password) {
        var sender = mail.requireSender();
        String normalized = validation.email(email);
        validation.newPassword(password, "password");
        String hash = passwords.encode(password);
        IdentityMail message;
        try {
            message = transaction.execute(() -> {
                if (users.findByEmail(normalized).isPresent()) {
                    return null;
                }
                var now = time.now();
                var user = users.create(new UserEntity(ids.next(), normalized, false, now, now));
                credentials.create(new PasswordCredentialEntity(user.getId(), hash, now));
                return tokens.issue(user.getId(), TokenPurpose.EMAIL_VERIFICATION);
            });
        } catch (DuplicateUserException conflict) {
            // Catch only after REQUIRES_NEW has rolled back, never inside a doomed transaction.
            return;
        }
        mail.send(sender, message);
    }
}
