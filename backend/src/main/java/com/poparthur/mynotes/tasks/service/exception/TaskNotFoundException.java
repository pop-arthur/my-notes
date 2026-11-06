package com.poparthur.mynotes.tasks.service.exception;

public class TaskNotFoundException extends RuntimeException {
    public TaskNotFoundException() {
        super("Task not found");
    }
}
