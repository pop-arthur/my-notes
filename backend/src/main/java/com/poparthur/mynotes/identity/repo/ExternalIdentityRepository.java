package com.poparthur.mynotes.identity.repo;

import com.poparthur.mynotes.identity.repo.entity.ExternalIdentityEntity;
import java.util.Optional;

/** Storage contract used by services. Implemented by the single JPA repository bean. */
public interface ExternalIdentityRepository {
    /** Insert only: an existing identifier or unique key must fail without overwriting a record. */
    ExternalIdentityEntity create(ExternalIdentityEntity entity);

    Optional<ExternalIdentityEntity> findByIssuerAndSubject(String issuer, String subject);
}
