package com.poparthur.mynotes.shared.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(URI type, String title, int status, String code, URI instance,
                         List<Map<String, String>> errors) {
    public ApiProblem withErrors(List<Map<String, String>> errors) {
        return new ApiProblem(type, title, status, code, instance, errors);
    }
}
