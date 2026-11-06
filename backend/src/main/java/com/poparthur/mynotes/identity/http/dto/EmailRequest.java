package com.poparthur.mynotes.identity.http.dto;

import jakarta.validation.constraints.NotNull;

public record EmailRequest(@NotNull String email) {
}
