package com.poparthur.mynotes.tasks.http.dto;

import jakarta.validation.constraints.NotNull;

public record CreateTaskRequest(@NotNull String title) {}
