package com.poparthur.mynotes.identity.repo;

import com.poparthur.mynotes.identity.repo.entity.PasswordCredentialEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Storage contract used by services. Implemented by the single JPA repository bean. */
public interface PasswordCredentialRepository {
    /** Insert only: an existing identifier or unique key must fail without overwriting a record. */
    PasswordCredentialEntity create(PasswordCredentialEntity entity);

    Optional<PasswordCredentialEntity> findByUserId(UUID userId);

    boolean updatePasswordHash(UUID userId, String passwordHash, Instant now);
}
