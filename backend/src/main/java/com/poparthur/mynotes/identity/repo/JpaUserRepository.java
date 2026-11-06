package com.poparthur.mynotes.identity.repo;

import com.poparthur.mynotes.identity.repo.entity.UserEntity;
import com.poparthur.mynotes.identity.repo.exception.DuplicateUserException;
import com.poparthur.mynotes.shared.repo.InsertOperations;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaUserRepository implements UserRepository {
    private final EntityManager entityManager;

    public JpaUserRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    @Override
    public UserEntity create(UserEntity entity) {
        InsertOperations.insert(entityManager, entity, DuplicateUserException::new);
        return entity;
    }

    @Override
    public Optional<UserEntity> findById(UUID id) {
        return Optional.ofNullable(entityManager.find(UserEntity.class, id));
    }

    @Override
    public Optional<UserEntity> findByEmail(String normalizedEmail) {
        return entityManager.createQuery("select u from UserEntity u where u.email = :email", UserEntity.class)
                .setParameter("email", normalizedEmail).getResultList().stream().findFirst();
    }

    /** Requires a writable caller transaction. The lock lasts until commit/rollback. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public Optional<UserEntity> findByIdForUpdate(UUID id) {
        return Optional.ofNullable(entityManager.find(UserEntity.class, id, LockModeType.PESSIMISTIC_WRITE));
    }

    @Transactional
    @Override
    public boolean confirmEmail(UUID id, Instant now) {
        entityManager.flush();
        int changed = entityManager.createQuery(
                "update UserEntity u set u.emailVerified = true, u.updatedAt = :now where u.id = :id")
                .setParameter("now", now).setParameter("id", id).executeUpdate();
        entityManager.clear();
        return changed == 1;
    }
}
