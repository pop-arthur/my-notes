package com.poparthur.mynotes.shared.service;

import java.net.URI;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Trusted configuration only. Never derive account links from request Host/forwarded headers. */
@Component
public class PublicOrigin {
    private final URI origin;

    public PublicOrigin(@Value("${app.public-origin:}") String value, Environment environment) {
        origin = value == null || value.isBlank() ? null : parse(value, environment.acceptsProfiles(Profiles.of("local", "test")));
    }

    public Optional<URI> configured() {
        return Optional.ofNullable(origin);
    }

    public URI require() {
        if (origin == null) {
            throw new IllegalStateException("APP_PUBLIC_ORIGIN is required");
        }
        return origin;
    }

    private static URI parse(String value, boolean local) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                    || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535
                    || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) {
                throw new IllegalArgumentException();
            }
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "", null, null);
        } catch (Exception invalid) {
            throw new IllegalStateException("APP_PUBLIC_ORIGIN must be an HTTPS origin without credentials, path, query or fragment");
        }
    }
}
