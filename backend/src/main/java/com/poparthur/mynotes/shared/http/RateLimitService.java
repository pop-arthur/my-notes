package com.poparthur.mynotes.shared.http;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RateLimitService {
    private final Clock clock;
    private final Cache<Key, Window> windows;
    private final Map<String, Policy> policies;

    public RateLimitService(Clock clock, @Value("${app.rate-limit.maximum-keys:10000}") long maximumKeys,
                            @Value("${app.rate-limit.login:20}") int login,
                            @Value("${app.rate-limit.token:20}") int token,
                            @Value("${app.rate-limit.mail:5}") int mail,
                            @Value("${app.rate-limit.google:10}") int google,
                            @Value("${app.rate-limit.csrf:60}") int csrf) {
        this.clock = clock;
        if (maximumKeys < 1 || Math.min(Math.min(login, token), Math.min(mail, Math.min(google, csrf))) < 1) {
            throw new IllegalArgumentException("Rate limits must be positive");
        }
        windows = Caffeine.newBuilder().maximumSize(maximumKeys).expireAfterAccess(Duration.ofMinutes(11)).build();
        policies = Map.of("login", new Policy(login, 60), "token", new Policy(token, 60),
                "mail", new Policy(mail, 600), "google", new Policy(google, 60), "csrf", new Policy(csrf, 60));
    }

    /** Zero means allowed; otherwise seconds until retry. Keys use the socket peer or the authenticated edge client, never arbitrary forwarded headers. */
    public long acquire(String route, String peer, String group) {
        Policy policy = policies.get(group);
        Window window = windows.get(new Key(route, peer), key -> new Window());
        synchronized (window) {
            long now = clock.millis();
            if (window.startedAt == Long.MIN_VALUE || now - window.startedAt >= policy.seconds * 1000L) {
                window.startedAt = now;
                window.count = 0;
            }
            if (window.count >= policy.limit) {
                return Math.max(1, (window.startedAt + policy.seconds * 1000L - now + 999) / 1000);
            }
            window.count++;
            return 0;
        }
    }

    private record Key(String route, String peer) {}
    private record Policy(int limit, long seconds) {}
    private static class Window {
        long startedAt = Long.MIN_VALUE;
        int count;
    }
}
