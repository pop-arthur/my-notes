package com.poparthur.mynotes.identity.service.mail;

/** No production implementation is supplied yet. Never log the raw token. */
@FunctionalInterface
public interface IdentityMailSender {
    void send(IdentityMail message);
}
