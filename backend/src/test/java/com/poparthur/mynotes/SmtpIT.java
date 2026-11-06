package com.poparthur.mynotes;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"app.proxy.secret=",
        "app.mail.enabled=true", "app.mail.tls-mode=none", "app.mail.username=", "app.mail.password=",
        "app.mail.from=no-reply@example.test", "app.public-origin=https://public.example.test",
        "app.oauth.google.enabled=false", "app.session.cookie-secure=false",
        "app.rate-limit.mail=1000", "app.rate-limit.login=1000", "app.rate-limit.token=1000", "app.rate-limit.csrf=1000"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SmtpIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withStartupTimeout(Duration.ofSeconds(180));
    @Container static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.31.1")
            .withExposedPorts(1025, 8025).waitingFor(Wait.forHttp("/").forPort(8025))
            .withStartupTimeout(Duration.ofSeconds(180));
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("app.mail.host", MAILPIT::getHost);
        r.add("app.mail.port", () -> MAILPIT.getMappedPort(1025));
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired JavaMailSender transport;
    final HttpClient mailClient = HttpClient.newHttpClient();
    HttpClient browser;
    String csrf;

    @BeforeEach void clean() throws Exception {
        jdbc.execute("TRUNCATE spring_session_attributes, spring_session, tasks, email_tokens, external_identities, password_credentials, users");
        mailClient.send(HttpRequest.newBuilder(mailUrl("/api/v1/messages")).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        browser = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        csrf = json.readTree(browser.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body()).get("token").asString();
    }

    @Test void registrationAndRecoveryDeliverRealUtf8MimeMessages() throws Exception {
        post("/api/auth/register", Map.of("email", "one@example.test", "password", "long initial password"), 202);
        var verification = received(1);
        assertThat(verification.get("Subject").asString()).contains("Подтвердите email");
        assertThat(verification.get("From").get("Address").asString()).isEqualTo("no-reply@example.test");
        assertThat(verification.get("To").get(0).get("Address").asString()).isEqualTo("one@example.test");
        assertThat(verification.get("Text").asString()).contains("Подтвердить email", "https://public.example.test/verify-email?token=");
        assertThat(verification.get("HTML").asString()).contains("Подтвердить email", "<a href=");
        post("/api/auth/verify-email", Map.of("token", token(verification, "verify-email")), 204);
        post("/api/auth/login", Map.of("email", "one@example.test", "password", "long initial password"), 200);
        refreshCsrf();
        post("/api/auth/forgot-password", Map.of("email", "one@example.test"), 202);
        var reset = received(2);
        assertThat(reset.get("Subject").asString()).contains("Сброс пароля");
        post("/api/auth/reset-password", Map.of("token", token(reset, "reset-password"), "newPassword", "new longer password"), 204);
        refreshCsrf();
        post("/api/auth/login", Map.of("email", "one@example.test", "password", "new longer password"), 200);
    }

    @Test void smtpOutageKeepsCommittedDataAndResendRecovers() throws Exception {
        var smtp = (JavaMailSenderImpl) transport;
        int realPort = smtp.getPort();
        // Accept the TCP connection but provide no SMTP greeting: exercise the real 5-second timeout.
        try (var silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            smtp.setHost(InetAddress.getLoopbackAddress().getHostAddress()); smtp.setPort(silent.getLocalPort());
            try {
                var failed = post("/api/auth/register", Map.of("email", "one@example.test", "password", "long initial password"), 503);
                assertThat(failed.body()).contains("mail_delivery_failed").doesNotContain("long initial password", "SMTP", "token=");
            } finally {
                smtp.setHost(MAILPIT.getHost()); smtp.setPort(realPort);
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_credentials", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_tokens", Long.class)).isEqualTo(1);
        post("/api/auth/resend-verification", Map.of("email", "one@example.test"), 202);
        var replacement = received(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_tokens WHERE consumed_at IS NOT NULL", Long.class)).isEqualTo(1);
        post("/api/auth/verify-email", Map.of("token", token(replacement, "verify-email")), 204);
        post("/api/auth/login", Map.of("email", "one@example.test", "password", "long initial password"), 200);
    }

    void refreshCsrf() throws Exception {
        csrf = json.readTree(browser.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body()).get("token").asString();
    }
    HttpResponse<String> post(String path, Object body, int expected) throws Exception {
        var response = browser.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", csrf)
                .header("X-Forwarded-Host", "evil.example").header("X-Forwarded-Proto", "http")
                .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + ": " + response.body()).isEqualTo(expected);
        return response;
    }
    JsonNode received(int expected) throws Exception {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(list().get("total").asInt()).isEqualTo(expected));
        String id = list().get("messages").get(0).get("ID").asString();
        return json.readTree(mailClient.send(HttpRequest.newBuilder(mailUrl("/api/v1/message/" + id)).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
    }
    JsonNode list() throws Exception {
        return json.readTree(mailClient.send(HttpRequest.newBuilder(mailUrl("/api/v1/messages")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
    }
    URI mailUrl(String path) { return URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path); }
    String token(JsonNode mail, String path) {
        var matcher = Pattern.compile("https://public\\.example\\.test/" + path + "\\?token=([A-Za-z0-9_-]+)").matcher(mail.get("Text").asString());
        assertThat(matcher.find()).isTrue(); return matcher.group(1);
    }
}
