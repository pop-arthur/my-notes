package com.poparthur.mynotes.identity.http.dto;

import jakarta.validation.constraints.NotNull;

public record LoginRequest(@NotNull String email, @NotNull String password) {
    @Override public String toString() { return "LoginRequest[redacted]"; }
}
