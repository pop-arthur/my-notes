package com.poparthur.mynotes.identity.service.result;

import java.time.Instant;
import java.util.UUID;

public record UserResult(UUID id, String email, boolean emailVerified, Instant createdAt) {
}
