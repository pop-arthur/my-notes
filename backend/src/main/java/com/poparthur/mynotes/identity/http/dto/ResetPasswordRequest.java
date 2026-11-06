package com.poparthur.mynotes.identity.http.dto;

import jakarta.validation.constraints.NotNull;

public record ResetPasswordRequest(@NotNull String token, @NotNull String newPassword) {
    @Override public String toString() { return "ResetPasswordRequest[redacted]"; }
}
