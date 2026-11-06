package com.poparthur.mynotes.identity.repo.exception;

public class DuplicateEmailTokenException extends RuntimeException {
    public DuplicateEmailTokenException(Throwable cause) {
        super("Record already exists", cause);
    }
}
