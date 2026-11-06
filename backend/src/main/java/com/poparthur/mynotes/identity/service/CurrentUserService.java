package com.poparthur.mynotes.identity.service;

import com.poparthur.mynotes.identity.repo.UserRepository;
import com.poparthur.mynotes.identity.service.exception.EmailNotVerifiedException;
import com.poparthur.mynotes.identity.service.exception.InvalidCredentialsException;
import com.poparthur.mynotes.identity.service.result.UserResult;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CurrentUserService {
    private final UserRepository users;

    public CurrentUserService(UserRepository users) {
        this.users = users;
    }

    public UserResult requireVerified(UUID userId) {
        if (userId == null) {
            throw new InvalidCredentialsException();
        }
        var user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }
        return IdentityResults.user(user);
    }
    public boolean isSessionValid(UUID userId, long authVersion) {
        if (userId == null) {
            return false;
        }
        return users.findById(userId)
                .filter(user -> user.isEmailVerified() && user.getAuthVersion() == authVersion).isPresent();
    }
}
