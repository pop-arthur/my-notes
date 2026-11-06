package com.poparthur.mynotes.identity.http.dto;

public record CsrfResponse(String headerName, String token) {
    @Override public String toString() { return "CsrfResponse[redacted]"; }
}
