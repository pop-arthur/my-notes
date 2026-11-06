package com.poparthur.mynotes.identity.service.result;

import java.util.UUID;

public record PasswordResetResult(UUID userId, long authVersion) {
}
