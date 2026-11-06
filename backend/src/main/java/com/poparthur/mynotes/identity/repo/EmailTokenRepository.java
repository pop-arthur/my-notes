package com.poparthur.mynotes.identity.repo;

import com.poparthur.mynotes.identity.repo.entity.EmailTokenEntity;
import com.poparthur.mynotes.identity.repo.entity.TokenPurpose;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Storage contract used by services. Implemented by the single JPA repository bean. */
public interface EmailTokenRepository {
    /** Insert only: an existing identifier or unique key must fail without overwriting a record. */
    EmailTokenEntity create(EmailTokenEntity entity);

    /** Requires a writable caller transaction; atomic consumption rolls back with that transaction. */
    Optional<EmailTokenEntity> consume(String tokenHash, TokenPurpose purpose, Instant now);

    int revokeByUserIdAndPurpose(UUID userId, TokenPurpose purpose, Instant now);
}
