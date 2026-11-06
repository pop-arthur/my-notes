package com.poparthur.mynotes.identity.service;

import com.poparthur.mynotes.identity.service.event.PasswordResetCompleted;
import com.poparthur.mynotes.identity.service.result.SessionIdentity;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class SessionRevocationService {
    private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> repositories;

    public SessionRevocationService(ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> repositories) {
        this.repositories = repositories;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterPasswordReset(PasswordResetCompleted event) {
        var repository = repositories.getIfAvailable();
        if (repository == null) {
            return; // Non-web service tests have no session store; the version check remains authoritative.
        }
        try {
            repository.findByPrincipalName(event.userId().toString()).forEach((id, session) -> {
                SessionIdentity identity = session.getAttribute(SessionIdentity.ATTRIBUTE);
                if (identity != null && identity.authVersion() < event.authVersion()) {
                    repository.deleteById(id);
                }
            });
        } catch (RuntimeException failure) {
            // Revocation is still enforced by the database auth_version on every authenticated request.
            LoggerFactory.getLogger(getClass()).error("Session cleanup failed after password reset");
        }
    }
}
