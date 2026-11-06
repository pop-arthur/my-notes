package com.poparthur.mynotes.identity.service.result;

import java.util.UUID;

/** Internal session snapshot. authVersion must be checked by the future session integration. */
public record AuthenticationResult(UUID userId, long authVersion, UserResult user) {
}
