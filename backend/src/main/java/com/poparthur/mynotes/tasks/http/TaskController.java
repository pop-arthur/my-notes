package com.poparthur.mynotes.tasks.http;

import com.poparthur.mynotes.identity.service.result.SessionIdentity;
import com.poparthur.mynotes.tasks.http.dto.*;
import com.poparthur.mynotes.tasks.service.TaskService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService tasks;

    public TaskController(TaskService tasks) { this.tasks = tasks; }

    @GetMapping
    public List<TaskResponse> list(@AuthenticationPrincipal SessionIdentity actor) {
        return tasks.list(actor.userId()).stream().map(TaskResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(@AuthenticationPrincipal SessionIdentity actor,
                                               @Valid @RequestBody CreateTaskRequest request) {
        var task = tasks.create(actor.userId(), request.title());
        return ResponseEntity.created(URI.create("/api/tasks/" + task.id())).body(TaskResponse.from(task));
    }

    @GetMapping("/{id}")
    public TaskResponse get(@AuthenticationPrincipal SessionIdentity actor, @PathVariable UUID id) {
        return TaskResponse.from(tasks.get(actor.userId(), id));
    }

    @PatchMapping("/{id}")
    public TaskResponse update(@AuthenticationPrincipal SessionIdentity actor, @PathVariable UUID id,
                                @RequestBody UpdateTaskRequest request) {
        return TaskResponse.from(tasks.update(actor.userId(), id, request.title(), request.completed()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal SessionIdentity actor, @PathVariable UUID id) {
        tasks.delete(actor.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
