package com.poparthur.mynotes.shared.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ProblemWriter {
    private final JsonMapper json;

    public ProblemWriter(JsonMapper json) {
        this.json = json;
    }

    public ApiProblem problem(int status, String code, HttpServletRequest request) {
        var httpStatus = HttpStatus.resolve(status);
        URI instance;
        try {
            instance = URI.create(request.getRequestURI());
        } catch (IllegalArgumentException invalidPath) {
            instance = URI.create("/");
        }
        return new ApiProblem(URI.create("about:blank"), httpStatus == null ? "Request failed" : httpStatus.getReasonPhrase(),
                status, code, instance, null);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), problem(status, code, request));
    }
}
