package com.poparthur.mynotes.identity.service.result;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/** Persisted local identity only: never store Google tokens or password credentials here. */
public record SessionIdentity(UUID userId, long authVersion) implements Principal, Serializable {
    @Serial private static final long serialVersionUID = 1L;
    public static final String ATTRIBUTE = "MY_NOTES_IDENTITY";

    @Override
    public String getName() {
        return userId.toString();
    }
}
