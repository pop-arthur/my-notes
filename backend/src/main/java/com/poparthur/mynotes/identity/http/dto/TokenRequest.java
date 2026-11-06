package com.poparthur.mynotes.identity.http.dto;

import jakarta.validation.constraints.NotNull;

public record TokenRequest(@NotNull String token) {
    @Override public String toString() { return "TokenRequest[redacted]"; }
}
