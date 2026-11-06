package com.poparthur.mynotes.identity.http.dto;

import jakarta.validation.constraints.NotNull;

public record RegisterRequest(@NotNull String email, @NotNull String password) {
    @Override public String toString() { return "RegisterRequest[redacted]"; }
}
