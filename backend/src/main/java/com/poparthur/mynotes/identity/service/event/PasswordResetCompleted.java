package com.poparthur.mynotes.identity.service.event;

import java.util.UUID;

public record PasswordResetCompleted(UUID userId, long authVersion) {
}
