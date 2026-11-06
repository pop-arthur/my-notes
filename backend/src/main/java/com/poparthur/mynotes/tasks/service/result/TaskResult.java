package com.poparthur.mynotes.tasks.service.result;

import java.time.Instant;
import java.util.UUID;

public record TaskResult(UUID id, String title, boolean completed, Instant createdAt, Instant updatedAt) {
}
