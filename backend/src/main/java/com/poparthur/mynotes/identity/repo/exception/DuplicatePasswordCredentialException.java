package com.poparthur.mynotes.identity.repo.exception;

public class DuplicatePasswordCredentialException extends RuntimeException {
    public DuplicatePasswordCredentialException(Throwable cause) {
        super("Record already exists", cause);
    }
}
