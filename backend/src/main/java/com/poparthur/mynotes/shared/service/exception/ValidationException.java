package com.poparthur.mynotes.shared.service.exception;

public class ValidationException extends RuntimeException {
    private final String field;

    public ValidationException(String field) {
        super("Invalid input for field: " + field);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
