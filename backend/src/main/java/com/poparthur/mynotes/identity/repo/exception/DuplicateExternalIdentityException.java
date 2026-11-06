package com.poparthur.mynotes.identity.repo.exception;

public class DuplicateExternalIdentityException extends RuntimeException {
    public DuplicateExternalIdentityException(Throwable cause) {
        super("Record already exists", cause);
    }
}
