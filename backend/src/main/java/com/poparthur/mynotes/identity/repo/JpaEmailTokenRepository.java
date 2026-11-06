package com.poparthur.mynotes.identity.repo;

import com.poparthur.mynotes.identity.repo.entity.EmailTokenEntity;
import com.poparthur.mynotes.identity.repo.entity.TokenPurpose;
import com.poparthur.mynotes.identity.repo.exception.DuplicateEmailTokenException;
import com.poparthur.mynotes.shared.repo.InsertOperations;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaEmailTokenRepository implements EmailTokenRepository {
    private final EntityManager entityManager;

    public JpaEmailTokenRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    @Override
    public EmailTokenEntity create(EmailTokenEntity entity) {
        InsertOperations.insert(entityManager, entity, DuplicateEmailTokenException::new);
        return entity;
    }

    @Override
    public Optional<EmailTokenEntity> findByHashAndPurpose(String tokenHash, TokenPurpose purpose) {
        return entityManager.createQuery(
                "select t from EmailTokenEntity t where t.tokenHash = :tokenHash and t.purpose = :purpose", EmailTokenEntity.class)
                .setParameter("tokenHash", tokenHash).setParameter("purpose", purpose)
                .getResultList().stream().findFirst();
    }

    /** Requires a writable caller transaction; consumption rolls back with that transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public Optional<EmailTokenEntity> consume(String tokenHash, TokenPurpose purpose, Instant now) {
        entityManager.flush();
        int changed = entityManager.createQuery(
                "update EmailTokenEntity t set t.consumedAt = :now where t.tokenHash = :tokenHash and t.purpose = :purpose and t.consumedAt is null and t.expiresAt > :now")
                .setParameter("now", now).setParameter("tokenHash", tokenHash).setParameter("purpose", purpose).executeUpdate();
        entityManager.clear();
        if (changed == 0) {
            return Optional.empty();
        }
        return entityManager.createQuery("select t from EmailTokenEntity t where t.tokenHash = :tokenHash", EmailTokenEntity.class)
                .setParameter("tokenHash", tokenHash).getResultList().stream().findFirst();
    }

    /** Marks all unconsumed tokens of this purpose consumed, including expired tokens. */
    @Transactional
    @Override
    public int revokeByUserIdAndPurpose(UUID userId, TokenPurpose purpose, Instant now) {
        entityManager.flush();
        int changed = entityManager.createQuery(
                "update EmailTokenEntity t set t.consumedAt = :now where t.userId = :userId and t.purpose = :purpose and t.consumedAt is null")
                .setParameter("now", now).setParameter("userId", userId).setParameter("purpose", purpose).executeUpdate();
        entityManager.clear();
        return changed;
    }
}
