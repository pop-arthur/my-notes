package com.poparthur.mynotes.tasks.http.dto;

import com.poparthur.mynotes.tasks.service.result.TaskResult;
import java.time.Instant;
import java.util.UUID;

public record TaskResponse(UUID id, String title, boolean completed, Instant createdAt, Instant updatedAt) {
    public static TaskResponse from(TaskResult task) {
        return new TaskResponse(task.id(), task.title(), task.completed(), task.createdAt(), task.updatedAt());
    }
}
