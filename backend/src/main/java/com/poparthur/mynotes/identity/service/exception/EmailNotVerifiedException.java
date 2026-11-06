package com.poparthur.mynotes.identity.service.exception;

public class EmailNotVerifiedException extends RuntimeException {
    public EmailNotVerifiedException() {
        super("Email is not verified");
    }
}
