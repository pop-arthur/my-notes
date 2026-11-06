package com.poparthur.mynotes.tasks.http.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.poparthur.mynotes.shared.service.exception.ValidationException;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Distinguishes omitted fields from explicit null without changing service-layer types. */
public record UpdateTaskRequest(String title, Boolean completed) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static UpdateTaskRequest fromJson(JsonNode body) {
        if (!body.isObject() || body.isEmpty() || !Set.of("title", "completed").containsAll(body.propertyNames())) {
            throw new ValidationException("body");
        }
        var title = body.get("title");
        var completed = body.get("completed");
        if (title != null && !title.isString()) {
            throw new ValidationException("title");
        }
        if (completed != null && !completed.isBoolean()) {
            throw new ValidationException("completed");
        }
        return new UpdateTaskRequest(title == null ? null : title.asString(), completed == null ? null : completed.asBoolean());
    }
}
