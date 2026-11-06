package com.poparthur.mynotes.identity.service.exception;

public class MailDeliveryFailureException extends RuntimeException {
    public MailDeliveryFailureException() {
        super("Email delivery failed");
    }
}
