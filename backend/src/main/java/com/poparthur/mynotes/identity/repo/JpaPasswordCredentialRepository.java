package com.poparthur.mynotes.identity.repo;

import com.poparthur.mynotes.identity.repo.entity.PasswordCredentialEntity;
import com.poparthur.mynotes.identity.repo.exception.DuplicatePasswordCredentialException;
import com.poparthur.mynotes.shared.repo.InsertOperations;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaPasswordCredentialRepository implements PasswordCredentialRepository {
    private final EntityManager entityManager;

    public JpaPasswordCredentialRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    @Override
    public PasswordCredentialEntity create(PasswordCredentialEntity entity) {
        InsertOperations.insert(entityManager, entity, DuplicatePasswordCredentialException::new);
        return entity;
    }

    @Override
    public Optional<PasswordCredentialEntity> findByUserId(UUID userId) {
        return Optional.ofNullable(entityManager.find(PasswordCredentialEntity.class, userId));
    }

    @Transactional
    @Override
    public boolean updatePasswordHash(UUID userId, String passwordHash, Instant now) {
        entityManager.flush();
        int changed = entityManager.createQuery(
                "update PasswordCredentialEntity c set c.passwordHash = :passwordHash, c.updatedAt = :now where c.userId = :userId")
                .setParameter("passwordHash", passwordHash).setParameter("now", now)
                .setParameter("userId", userId).executeUpdate();
        entityManager.clear();
        return changed == 1;
    }
}
