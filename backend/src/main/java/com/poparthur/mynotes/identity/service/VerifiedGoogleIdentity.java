package com.poparthur.mynotes.identity.service;

/** Trusted internal input from a future Google ID-token verifier, never an HTTP request DTO. */
public record VerifiedGoogleIdentity(String issuer, String subject, String email, boolean emailVerified) {
}
