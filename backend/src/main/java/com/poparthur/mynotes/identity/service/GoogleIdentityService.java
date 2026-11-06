package com.poparthur.mynotes.identity.service;

import com.poparthur.mynotes.identity.repo.ExternalIdentityRepository;
import com.poparthur.mynotes.identity.repo.UserRepository;
import com.poparthur.mynotes.identity.repo.entity.ExternalIdentityEntity;
import com.poparthur.mynotes.identity.repo.entity.UserEntity;
import com.poparthur.mynotes.identity.repo.exception.DuplicateExternalIdentityException;
import com.poparthur.mynotes.identity.repo.exception.DuplicateUserException;
import com.poparthur.mynotes.identity.service.exception.GoogleAuthenticationException;
import com.poparthur.mynotes.identity.service.result.AuthenticationResult;
import com.poparthur.mynotes.shared.service.InputValidation;
import com.poparthur.mynotes.shared.service.NewTransaction;
import com.poparthur.mynotes.shared.service.ServiceTime;
import com.poparthur.mynotes.shared.service.UuidGenerator;
import com.poparthur.mynotes.shared.service.exception.ValidationException;
import org.springframework.stereotype.Service;

@Service
public class GoogleIdentityService {
    private static final String ISSUER = "https://accounts.google.com";
    private final UserRepository users;
    private final ExternalIdentityRepository identities;
    private final InputValidation validation;
    private final NewTransaction transaction;
    private final ServiceTime time;
    private final UuidGenerator ids;

    public GoogleIdentityService(UserRepository users, ExternalIdentityRepository identities,
                                 InputValidation validation, NewTransaction transaction,
                                 ServiceTime time, UuidGenerator ids) {
        this.users = users;
        this.identities = identities;
        this.validation = validation;
        this.transaction = transaction;
        this.time = time;
        this.ids = ids;
    }

    public AuthenticationResult authenticate(VerifiedGoogleIdentity identity) {
        if (identity == null || !identity.emailVerified()
                || !(ISSUER.equals(identity.issuer()) || "accounts.google.com".equals(identity.issuer()))
                || identity.subject() == null || identity.subject().isBlank() || identity.subject().length() > 255) {
            throw new GoogleAuthenticationException();
        }
        String email;
        try {
            email = validation.email(identity.email());
        } catch (ValidationException failure) {
            throw new GoogleAuthenticationException();
        }
        try {
            return transaction.execute(() -> {
                var existing = existing(identity.subject());
                if (existing != null) {
                    return existing;
                }
                if (users.findByEmail(email).isPresent()) {
                    throw new GoogleAuthenticationException();
                }
                var now = time.now();
                var user = users.create(new UserEntity(ids.next(), email, true, now, now));
                identities.create(new ExternalIdentityEntity(ids.next(), user.getId(), ISSUER, identity.subject()));
                return IdentityResults.authentication(user);
            });
        } catch (DuplicateUserException | DuplicateExternalIdentityException conflict) {
            // The failed transaction has ended. A concurrent identical Google sign-in may have won.
            var result = transaction.execute(() -> existing(identity.subject()));
            if (result == null) {
                throw new GoogleAuthenticationException();
            }
            return result;
        }
    }

    private AuthenticationResult existing(String subject) {
        var identity = identities.findByIssuerAndSubject(ISSUER, subject).orElse(null);
        if (identity == null) {
            return null;
        }
        var user = users.findById(identity.getUserId()).orElseThrow(GoogleAuthenticationException::new);
        if (!user.isEmailVerified()) {
            throw new GoogleAuthenticationException();
        }
        return IdentityResults.authentication(user);
    }
}
