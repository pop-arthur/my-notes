package com.poparthur.mynotes.tasks.repo;

import com.poparthur.mynotes.tasks.repo.entity.TaskEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Storage contract used by services. Implemented by the single JPA repository bean. */
public interface TaskRepository {
    /** Insert only: an existing identifier or unique key must fail without overwriting a record. */
    TaskEntity create(TaskEntity entity);

    List<TaskEntity> findAllByUserId(UUID userId);

    Optional<TaskEntity> findByIdAndUserId(UUID id, UUID userId);

    Optional<TaskEntity> setCompleted(UUID id, UUID userId, boolean completed, Instant now);

    Optional<TaskEntity> update(UUID id, UUID userId, String title, Boolean completed, Instant now);

    boolean deleteByIdAndUserId(UUID id, UUID userId);
}
