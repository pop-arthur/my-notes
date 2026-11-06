package com.poparthur.mynotes.identity.service.exception;

public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException() {
        super("Invalid or expired token");
    }
}
