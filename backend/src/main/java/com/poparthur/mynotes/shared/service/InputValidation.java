package com.poparthur.mynotes.shared.service;

import com.poparthur.mynotes.shared.service.exception.ValidationException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class InputValidation {
    private final Validator validator;

    public InputValidation(Validator validator) {
        this.validator = validator;
    }

    public String email(String raw) {
        if (raw == null) {
            throw new ValidationException("email");
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (!validator.validate(new EmailInput(value)).isEmpty()) {
            throw new ValidationException("email");
        }
        return value;
    }

    public String newPassword(String value, String field) {
        return length(value, field, 12, 128);
    }

    public String loginPassword(String value) {
        return length(value, "password", 1, 128);
    }

    public String title(String raw) {
        if (raw == null) {
            throw new ValidationException("title");
        }
        if (raw.indexOf('\0') >= 0) {
            throw new ValidationException("title");
        }
        return length(raw.strip(), "title", 1, 200);
    }

    public UUID id(UUID value, String field) {
        if (value == null) {
            throw new ValidationException(field);
        }
        return value;
    }

    private String length(String value, String field, int min, int max) {
        if (value == null) {
            throw new ValidationException(field);
        }
        int count = value.codePointCount(0, value.length());
        if (count < min || count > max) {
            throw new ValidationException(field);
        }
        return value;
    }

    private record EmailInput(@NotBlank @Email @Size(max = 254) String value) {
    }
}
