package com.poparthur.mynotes.identity.service.mail;

/** SMTP implementation is enabled explicitly through MAIL_ENABLED. Never log the raw token. */
@FunctionalInterface
public interface IdentityMailSender {
    void send(IdentityMail message);
}
