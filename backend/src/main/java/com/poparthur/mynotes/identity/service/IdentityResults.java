package com.poparthur.mynotes.identity.service;

import com.poparthur.mynotes.identity.repo.entity.UserEntity;
import com.poparthur.mynotes.identity.service.result.AuthenticationResult;
import com.poparthur.mynotes.identity.service.result.UserResult;

final class IdentityResults {
    private IdentityResults() {
    }

    static UserResult user(UserEntity user) {
        return new UserResult(user.getId(), user.getEmail(), user.isEmailVerified(), user.getCreatedAt());
    }

    static AuthenticationResult authentication(UserEntity user) {
        return new AuthenticationResult(user.getId(), user.getAuthVersion(), user(user));
    }
}
