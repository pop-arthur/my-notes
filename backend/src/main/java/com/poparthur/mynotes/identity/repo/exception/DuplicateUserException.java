package com.poparthur.mynotes.identity.repo.exception;

public class DuplicateUserException extends RuntimeException {
    public DuplicateUserException(Throwable cause) {
        super("Record already exists", cause);
    }
}
