package com.poparthur.mynotes;

import com.poparthur.mynotes.shared.http.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class TrustedProxyTest {
    private static final String SECRET = "test-only-edge-secret-with-at-least-32-characters";
    private final ProblemWriter problems = new ProblemWriter(JsonMapper.builder().build());

    @Test void disabledProxyIgnoresAllSpoofedHeaders() throws Exception {
        var request = request("203.0.113.1", "wrong");
        var actual = new AtomicReference<String>();
        new TrustedProxyFilter("", problems).doFilter(request, new MockHttpServletResponse(),
                (req, res) -> actual.set(req.getRemoteAddr()));
        assertThat(actual.get()).isEqualTo("127.0.0.1");
    }

    @Test void missingOrWrongSecretAndInvalidAddressesAreDeniedWithoutSecrets() throws Exception {
        var filter = new TrustedProxyFilter(SECRET, problems);
        for (String ip : new String[]{"203.0.113.1", "example.org", "1.2.3.999", "127.1", "1.2.3.4,5.6.7.8", "fe80::1%eth0"}) {
            var request = request(ip, ip.equals("203.0.113.1") ? "wrong" : SECRET);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> { throw new AssertionError("Untrusted request accepted"); });
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("access_denied").doesNotContain(SECRET, ip);
        }
        var missing = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/auth/csrf"), missing,
                (req, res) -> { throw new AssertionError("Missing credentials accepted"); });
        assertThat(missing.getStatus()).isEqualTo(403);
    }

    @Test void trustedClientsHaveSeparateLimitsAndForwardedDoesNotOverrideThem() throws Exception {
        var filter = new TrustedProxyFilter(SECRET, problems);
        var limits = new RateLimitFilter(new RateLimitService(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 100, 1, 1, 1, 1, 1), problems);
        for (int i = 0; i < 3; i++) {
            var request = request(i == 1 ? "2001:db8::1" : "203.0.113.1", SECRET);
            request.addHeader("X-Forwarded-For", "198.51.100." + i);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> limits.doFilter(req, res,
                    (r, s) -> ((jakarta.servlet.http.HttpServletResponse) s).setStatus(204)));
            assertThat(response.getStatus()).isEqualTo(i == 2 ? 429 : 204);
        }
    }

    @Test void duplicateHeadersAreRejected() throws Exception {
        var request = request("203.0.113.1", SECRET);
        request.addHeader(TrustedProxyFilter.CLIENT_HEADER, "198.51.100.1");
        var response = new MockHttpServletResponse();
        new TrustedProxyFilter(SECRET, problems).doFilter(request, response,
                (req, res) -> { throw new AssertionError("Duplicate client header accepted"); });
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test void statusOnlyHealthcheckIsPublicForGet() throws Exception {
        for (String peer : new String[]{"127.0.0.1", "203.0.113.1"}) {
            var request = new MockHttpServletRequest("GET", "/actuator/health"); request.setRemoteAddr(peer);
            var response = new MockHttpServletResponse();
            new TrustedProxyFilter(SECRET, problems).doFilter(request, response,
                    (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
            assertThat(response.getStatus()).isEqualTo(204);
        }
    }

    @Test void shortSecretsStopStartup() {
        assertThatThrownBy(() -> new TrustedProxyFilter("short", problems)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("short");
    }

    private MockHttpServletRequest request(String ip, String secret) {
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(TrustedProxyFilter.SECRET_HEADER, secret);
        request.addHeader(TrustedProxyFilter.CLIENT_HEADER, ip);
        return request;
    }
}
