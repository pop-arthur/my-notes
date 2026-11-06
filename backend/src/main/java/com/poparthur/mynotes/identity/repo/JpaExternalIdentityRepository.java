package com.poparthur.mynotes.identity.repo;

import com.poparthur.mynotes.identity.repo.entity.ExternalIdentityEntity;
import com.poparthur.mynotes.identity.repo.exception.DuplicateExternalIdentityException;
import com.poparthur.mynotes.shared.repo.InsertOperations;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaExternalIdentityRepository implements ExternalIdentityRepository {
    private final EntityManager entityManager;

    public JpaExternalIdentityRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    @Override
    public ExternalIdentityEntity create(ExternalIdentityEntity entity) {
        InsertOperations.insert(entityManager, entity, DuplicateExternalIdentityException::new);
        return entity;
    }

    @Override
    public Optional<ExternalIdentityEntity> findByIssuerAndSubject(String issuer, String subject) {
        return entityManager.createQuery(
                "select i from ExternalIdentityEntity i where i.issuer = :issuer and i.subject = :subject", ExternalIdentityEntity.class)
                .setParameter("issuer", issuer).setParameter("subject", subject).getResultList().stream().findFirst();
    }
}
