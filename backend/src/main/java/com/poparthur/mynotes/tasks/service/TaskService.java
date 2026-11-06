package com.poparthur.mynotes.tasks.service;

import com.poparthur.mynotes.identity.service.CurrentUserService;
import com.poparthur.mynotes.shared.service.InputValidation;
import com.poparthur.mynotes.shared.service.ServiceTime;
import com.poparthur.mynotes.shared.service.UuidGenerator;
import com.poparthur.mynotes.tasks.repo.TaskRepository;
import com.poparthur.mynotes.tasks.repo.entity.TaskEntity;
import com.poparthur.mynotes.tasks.service.exception.TaskNotFoundException;
import com.poparthur.mynotes.tasks.service.result.TaskResult;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TaskService {
    private final TaskRepository tasks;
    private final CurrentUserService users;
    private final InputValidation validation;
    private final ServiceTime time;
    private final UuidGenerator ids;

    public TaskService(TaskRepository tasks, CurrentUserService users, InputValidation validation,
                       ServiceTime time, UuidGenerator ids) {
        this.tasks = tasks;
        this.users = users;
        this.validation = validation;
        this.time = time;
        this.ids = ids;
    }

    @Transactional
    public TaskResult create(UUID userId, String title) {
        users.requireVerified(userId);
        String normalizedTitle = validation.title(title);
        var now = time.now();
        return result(tasks.create(new TaskEntity(ids.next(), userId, normalizedTitle, false, now, now)));
    }

    public List<TaskResult> list(UUID userId) {
        users.requireVerified(userId);
        return tasks.findAllByUserId(userId).stream().map(TaskService::result).toList();
    }

    public TaskResult get(UUID userId, UUID taskId) {
        users.requireVerified(userId);
        validation.id(taskId, "taskId");
        return result(tasks.findByIdAndUserId(taskId, userId).orElseThrow(TaskNotFoundException::new));
    }

    @Transactional
    public TaskResult setCompleted(UUID userId, UUID taskId, boolean completed) {
        return update(userId, taskId, null, completed);
    }

    @Transactional
    public TaskResult update(UUID userId, UUID taskId, String title, Boolean completed) {
        users.requireVerified(userId);
        validation.id(taskId, "taskId");
        if (title == null && completed == null) {
            throw new com.poparthur.mynotes.shared.service.exception.ValidationException("body");
        }
        String normalizedTitle = title == null ? null : validation.title(title);
        return result(tasks.update(taskId, userId, normalizedTitle, completed, time.now())
                .orElseThrow(TaskNotFoundException::new));
    }

    @Transactional
    public void delete(UUID userId, UUID taskId) {
        users.requireVerified(userId);
        validation.id(taskId, "taskId");
        if (!tasks.deleteByIdAndUserId(taskId, userId)) {
            throw new TaskNotFoundException();
        }
    }

    private static TaskResult result(TaskEntity task) {
        return new TaskResult(task.getId(), task.getTitle(), task.isCompleted(), task.getCreatedAt(), task.getUpdatedAt());
    }
}
