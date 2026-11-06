package com.poparthur.mynotes;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.session.cookie-secure=false", "app.oauth.google.enabled=true", "app.oauth.google.client-id=test-client",
        "app.oauth.google.client-secret=test-secret", "app.oauth.google.redirect-uri=http://localhost:8080/login/oauth2/code/google",
        "app.rate-limit.google=1000", "app.rate-limit.csrf=1000"})
@ActiveProfiles("test")
@Import(GoogleOAuthIT.GoogleTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class GoogleOAuthIT {
    static final FakeGoogle GOOGLE = new FakeGoogle();
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withStartupTimeout(Duration.ofSeconds(180));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;
    @BeforeEach void clean() { jdbc.execute("TRUNCATE spring_session_attributes, spring_session, tasks, email_tokens, external_identities, password_credentials, users"); }
    @AfterAll static void stopGoogle() { GOOGLE.server.stop(0); }

    @Test void fullOidcFlowCreatesLocalSessionAndDoesNotStoreGoogleTokens() throws Exception {
        var browser = new Browser();
        var result = browser.login("valid", "one@example.com", "subject");
        assertThat(result.statusCode()).isEqualTo(302);
        assertThat(result.headers().firstValue("Location")).contains("/");
        var me = browser.get("/api/auth/me");
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        assertThat(me.body()).contains("one@example.com").doesNotContain("authVersion", "access-", "id_token");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_credentials", Long.class)).isZero();
        var principal = jdbc.queryForObject("SELECT id FROM users WHERE email='one@example.com'", UUID.class);
        var stored = sessions.findByPrincipalName(principal.toString());
        assertThat(stored).hasSize(1);
        var context = (org.springframework.security.core.context.SecurityContext) stored.values().iterator().next().getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(com.poparthur.mynotes.identity.service.result.SessionIdentity.class);
        for (byte[] bytes : jdbc.query("SELECT attribute_bytes FROM spring_session_attributes", (rs, row) -> rs.getBytes(1))) {
            assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain("access-", "LocalOidcUser", "OidcIdToken", "id_token");
        }
        assertThat(browser.get("/api/auth/csrf").statusCode()).isEqualTo(200);
    }

    @Test void cryptographicAndProtocolFailuresNeverCreateAnAccount() throws Exception {
        for (String mode : List.of("signature", "audience", "issuer", "expired", "state", "nonce", "unverified")) {
            var browser = new Browser();
            var result = browser.login(mode, mode + "@example.com", mode);
            assertThat(result.statusCode()).as(mode + ": " + result.body()).isEqualTo(302);
            assertThat(result.headers().firstValue("Location")).as(mode).contains("/login?error=oauth_failed");
            assertThat(browser.get("/api/auth/me").statusCode()).as(mode).isEqualTo(401);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isZero();
    }

    @Test void googleEmailConflictDoesNotMergeAccounts() throws Exception {
        assertThat(new Browser().login("valid", "one@example.com", "first").headers().firstValue("Location")).contains("/");
        var conflicting = new Browser();
        assertThat(conflicting.login("valid", "one@example.com", "second").headers().firstValue("Location"))
                .contains("/login?error=oauth_failed");
        assertThat(conflicting.get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM external_identities", Long.class)).isEqualTo(1);
    }

    class Browser {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> login(String mode, String email, String subject) throws Exception {
            var start = get("/oauth2/authorization/google?returnUrl=https://evil.example");
            assertThat(start.statusCode()).as(start.body()).isEqualTo(302);
            var query = parameters(URI.create(start.headers().firstValue("Location").orElseThrow()).getRawQuery());
            assertThat(query.get("scope")).contains("openid", "email", "profile");
            assertThat(query.get("nonce")).isNotBlank();
            String code = UUID.randomUUID().toString();
            GOOGLE.codes.put(code, new Scenario(mode, email, subject, query.get("nonce")));
            return get("/login/oauth2/code/google?code=" + code + "&state="
                    + URLEncoder.encode(mode.equals("state") ? "invalid-state" : query.get("state"), StandardCharsets.UTF_8));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GoogleTestConfiguration {
        @Bean @Primary ClientRegistrationRepository fakeGoogleClients() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("google")
                    .clientId("test-client").clientSecret("test-secret").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}").scope("openid", "email", "profile")
                    .authorizationUri(GOOGLE.url("/authorize")).tokenUri(GOOGLE.url("/token"))
                    .jwkSetUri(GOOGLE.url("/jwks")).issuerUri("https://accounts.google.com")
                    .userInfoUri(GOOGLE.url("/userinfo")).userNameAttributeName("sub").clientName("Test Google").build());
        }
    }

    record Scenario(String mode, String email, String subject, String nonce) {}
    static class FakeGoogle {
        final HttpServer server;
        final RSAKey key;
        final RSAKey wrong;
        final JsonMapper json = JsonMapper.builder().build();
        final Map<String, Scenario> codes = new ConcurrentHashMap<>();
        final Map<String, Scenario> accesses = new ConcurrentHashMap<>();
        FakeGoogle() {
            try {
                key = new RSAKeyGenerator(2048).keyID("test-key").generate();
                wrong = new RSAKeyGenerator(2048).keyID("test-key").generate();
                server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
                server.createContext("/jwks", exchange -> reply(exchange, new JWKSet(key.toPublicJWK()).toJSONObject()));
                server.createContext("/token", exchange -> {
                    try {
                        var form = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        var scenario = codes.remove(form.get("code"));
                        if (scenario == null) { exchange.sendResponseHeaders(400, -1); exchange.close(); return; }
                        String access = "access-" + UUID.randomUUID(); accesses.put(access, scenario);
                        reply(exchange, Map.of("access_token", access, "token_type", "Bearer", "expires_in", 3600,
                                "scope", "openid email profile", "id_token", sign(scenario)));
                    } catch (Exception failure) { exchange.sendResponseHeaders(500, -1); exchange.close(); }
                });
                server.createContext("/userinfo", exchange -> {
                    var scenario = accesses.get(exchange.getRequestHeaders().getFirst("Authorization").substring("Bearer ".length()));
                    reply(exchange, Map.of("sub", scenario.subject(), "email", scenario.email(), "email_verified", !scenario.mode().equals("unverified")));
                });
                server.start();
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        String url(String path) { return "http://localhost:" + server.getAddress().getPort() + path; }
        String sign(Scenario s) throws Exception {
            Instant now = Instant.now();
            var claims = new JWTClaimsSet.Builder().issuer(s.mode().equals("issuer") ? "https://evil.example" : "https://accounts.google.com")
                    .subject(s.subject()).audience(s.mode().equals("audience") ? "wrong-client" : "test-client")
                    .issueTime(Date.from(now.minusSeconds(s.mode().equals("expired") ? 3600 : 5)))
                    .expirationTime(Date.from(now.plusSeconds(s.mode().equals("expired") ? -1800 : 300)))
                    .claim("nonce", s.mode().equals("nonce") ? "invalid-nonce" : s.nonce())
                    .claim("email", s.email()).claim("email_verified", !s.mode().equals("unverified")).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(s.mode().equals("signature") ? wrong : key));
            return jwt.serialize();
        }
        void reply(com.sun.net.httpserver.HttpExchange exchange, Object body) throws java.io.IOException {
            byte[] bytes = json.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }
    }
    static Map<String, String> parameters(String query) {
        var result = new HashMap<String, String>();
        for (String pair : query.split("&")) {
            var bits = pair.split("=", 2);
            result.put(URLDecoder.decode(bits[0], StandardCharsets.UTF_8), bits.length > 1 ? URLDecoder.decode(bits[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }
}
