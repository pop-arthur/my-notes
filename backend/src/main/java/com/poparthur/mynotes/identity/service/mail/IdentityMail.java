package com.poparthur.mynotes.identity.service.mail;

/** Internal mail payload, never an API result. */
public record IdentityMail(String recipient, MailPurpose purpose, String token) {
    @Override
    public String toString() {
        return "IdentityMail[purpose=" + purpose + ", token=<redacted>]";
    }
}
