package com.poparthur.mynotes.identity.http.dto;

import com.poparthur.mynotes.identity.service.result.UserResult;
import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String email, boolean emailVerified, Instant createdAt) {
    public static UserResponse from(UserResult user) {
        return new UserResponse(user.id(), user.email(), user.emailVerified(), user.createdAt());
    }
}
