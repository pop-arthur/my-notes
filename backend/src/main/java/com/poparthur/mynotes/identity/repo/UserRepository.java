package com.poparthur.mynotes.identity.repo;

import com.poparthur.mynotes.identity.repo.entity.UserEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Storage contract used by services. Implemented by the single JPA repository bean. */
public interface UserRepository {
    /** Insert only: an existing identifier or unique key must fail without overwriting a record. */
    UserEntity create(UserEntity entity);

    Optional<UserEntity> findById(UUID id);

    Optional<UserEntity> findByEmail(String normalizedEmail);

    /** Requires a writable caller transaction; lock held until commit/rollback. */
    Optional<UserEntity> findByIdForUpdate(UUID id);

    boolean confirmEmail(UUID id, Instant now);

    /** Requires a writable caller transaction holding the user write lock. */
    boolean incrementAuthVersion(UUID id, Instant now);
}
