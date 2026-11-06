package com.poparthur.mynotes.identity.service.exception;

public class MailDeliveryUnavailableException extends RuntimeException {
    public MailDeliveryUnavailableException() {
        super("Email delivery is not configured");
    }
}
