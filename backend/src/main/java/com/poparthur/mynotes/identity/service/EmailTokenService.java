package com.poparthur.mynotes.identity.service;

import com.poparthur.mynotes.identity.repo.EmailTokenRepository;
import com.poparthur.mynotes.identity.repo.UserRepository;
import com.poparthur.mynotes.identity.repo.entity.EmailTokenEntity;
import com.poparthur.mynotes.identity.repo.entity.TokenPurpose;
import com.poparthur.mynotes.identity.service.exception.InvalidTokenException;
import com.poparthur.mynotes.identity.service.mail.IdentityMail;
import com.poparthur.mynotes.identity.service.mail.MailPurpose;
import com.poparthur.mynotes.shared.service.ServiceTime;
import com.poparthur.mynotes.shared.service.UuidGenerator;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal coordinator: token values are passed only to the mail boundary, never to HTTP. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class EmailTokenService {
    private final UserRepository users;
    private final EmailTokenRepository tokens;
    private final TokenCodec codec;
    private final ServiceTime time;
    private final UuidGenerator ids;

    public EmailTokenService(UserRepository users, EmailTokenRepository tokens, TokenCodec codec,
                             ServiceTime time, UuidGenerator ids) {
        this.users = users;
        this.tokens = tokens;
        this.codec = codec;
        this.time = time;
        this.ids = ids;
    }

    public IdentityMail issue(UUID userId, TokenPurpose purpose) {
        var user = users.findByIdForUpdate(userId).orElseThrow(InvalidTokenException::new);
        var now = time.now();
        tokens.revokeByUserIdAndPurpose(userId, purpose, now);
        String raw = codec.generate();
        Duration ttl = purpose == TokenPurpose.EMAIL_VERIFICATION ? Duration.ofHours(24) : Duration.ofMinutes(30);
        tokens.create(new EmailTokenEntity(ids.next(), userId, purpose, codec.hash(raw), now, now.plus(ttl), null));
        return new IdentityMail(user.getEmail(), MailPurpose.valueOf(purpose.name()), raw);
    }

    public EmailTokenEntity consume(String raw, TokenPurpose purpose) {
        String hash = codec.hash(raw);
        var candidate = tokens.findByHashAndPurpose(hash, purpose).orElseThrow(InvalidTokenException::new);
        // Always user -> token, the same lock order used by issue/resend/reset.
        users.findByIdForUpdate(candidate.getUserId()).orElseThrow(InvalidTokenException::new);
        return tokens.consume(hash, purpose, time.now()).orElseThrow(InvalidTokenException::new);
    }
}
