package com.poparthur.mynotes.tasks.repo.exception;

public class DuplicateTaskException extends RuntimeException {
    public DuplicateTaskException(Throwable cause) {
        super("Record already exists", cause);
    }
}
