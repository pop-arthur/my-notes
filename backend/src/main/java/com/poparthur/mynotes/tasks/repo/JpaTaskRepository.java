package com.poparthur.mynotes.tasks.repo;

import com.poparthur.mynotes.shared.repo.InsertOperations;
import com.poparthur.mynotes.tasks.repo.entity.TaskEntity;
import com.poparthur.mynotes.tasks.repo.exception.DuplicateTaskException;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaTaskRepository implements TaskRepository {
    private final EntityManager entityManager;

    public JpaTaskRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    @Override
    public TaskEntity create(TaskEntity entity) {
        InsertOperations.insert(entityManager, entity, DuplicateTaskException::new);
        return entity;
    }

    @Override
    public List<TaskEntity> findAllByUserId(UUID userId) {
        return entityManager.createQuery(
                "select t from TaskEntity t where t.userId = :userId order by t.createdAt desc, t.id desc", TaskEntity.class)
                .setParameter("userId", userId).getResultList();
    }

    @Override
    public Optional<TaskEntity> findByIdAndUserId(UUID id, UUID userId) {
        return entityManager.createQuery(
                "select t from TaskEntity t where t.id = :id and t.userId = :userId", TaskEntity.class)
                .setParameter("id", id).setParameter("userId", userId).getResultList().stream().findFirst();
    }

    @Transactional
    @Override
    public Optional<TaskEntity> setCompleted(UUID id, UUID userId, boolean completed, Instant now) {
        entityManager.flush();
        int changed = entityManager.createQuery(
                "update TaskEntity t set t.completed = :completed, t.updatedAt = :now where t.id = :id and t.userId = :userId")
                .setParameter("completed", completed).setParameter("now", now)
                .setParameter("id", id).setParameter("userId", userId).executeUpdate();
        entityManager.clear();
        return changed == 0 ? Optional.empty() : findByIdAndUserId(id, userId);
    }

    @Transactional
    @Override
    public boolean deleteByIdAndUserId(UUID id, UUID userId) {
        entityManager.flush();
        int changed = entityManager.createQuery("delete from TaskEntity t where t.id = :id and t.userId = :userId")
                .setParameter("id", id).setParameter("userId", userId).executeUpdate();
        entityManager.clear();
        return changed == 1;
    }
}
