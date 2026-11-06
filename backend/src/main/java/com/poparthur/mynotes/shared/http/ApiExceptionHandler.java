package com.poparthur.mynotes.shared.http;

import com.poparthur.mynotes.identity.service.exception.*;
import com.poparthur.mynotes.shared.service.exception.ValidationException;
import com.poparthur.mynotes.tasks.service.exception.TaskNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private final ProblemWriter problems;

    public ApiExceptionHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> handle(Exception failure, HttpServletRequest request) {
        int status;
        String code;
        if (failure instanceof ValidationException || failure instanceof HttpMessageNotReadableException
                || failure instanceof MethodArgumentNotValidException || failure instanceof MethodArgumentTypeMismatchException) {
            status = 400; code = "validation_failed";
        } else if (failure instanceof InvalidTokenException) {
            status = 400; code = "invalid_token";
        } else if (failure instanceof InvalidCredentialsException || failure instanceof BadCredentialsException) {
            status = 401; code = "invalid_credentials";
        } else if (failure instanceof EmailNotVerifiedException) {
            status = 403; code = "email_not_verified";
        } else if (failure instanceof TaskNotFoundException) {
            status = 404; code = "task_not_found";
        } else if (failure instanceof MailDeliveryUnavailableException) {
            status = 503; code = "mail_delivery_unavailable";
        } else if (failure instanceof MailDeliveryFailureException) {
            status = 503; code = "mail_delivery_failed";
        } else if (failure instanceof ErrorResponse error) {
            status = error.getStatusCode().value();
            code = switch (status) {
                case 404 -> "not_found";
                case 405 -> "method_not_allowed";
                case 415 -> "unsupported_media_type";
                default -> status >= 500 ? "internal_error" : "validation_failed";
            };
        } else {
            status = 500; code = "internal_error";
            LoggerFactory.getLogger(getClass()).error("Request failed with exception type {}", failure.getClass().getName());
        }
        var problem = problems.problem(status, code, request);
        if (failure instanceof ValidationException validation) {
            problem = problem.withErrors(List.of(Map.of("field", validation.getField(), "message", "Invalid value")));
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Cache-Control", "no-store").body(problem);
    }
}
