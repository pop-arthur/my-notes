package com.poparthur.mynotes.identity.service.exception;

public class GoogleAuthenticationException extends RuntimeException {
    public GoogleAuthenticationException() {
        super("Google authentication failed");
    }
}
