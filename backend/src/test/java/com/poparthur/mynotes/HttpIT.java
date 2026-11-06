package com.poparthur.mynotes;

import com.poparthur.mynotes.identity.service.mail.*;
import com.poparthur.mynotes.identity.service.result.SessionIdentity;
import jakarta.servlet.http.Cookie;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"app.proxy.secret=",
        "app.mail.enabled=false", "app.oauth.google.enabled=false", "app.public-origin=", "app.session.cookie-secure=false", "app.rate-limit.mail=1000", "app.rate-limit.login=1000",
        "app.rate-limit.token=1000", "app.rate-limit.google=1000", "app.rate-limit.csrf=1000"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(HttpIT.MailConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class HttpIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withStartupTimeout(Duration.ofSeconds(180));
    static final String PASSWORD = "password with unicode 😀";
    static final String NEW_PASSWORD = "new password with unicode 😀";
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Mailbox mail;
    @Autowired MockMvc mvc;
    @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;

    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE spring_session_attributes, spring_session, tasks, email_tokens, external_identities, password_credentials, users");
        mail.messages.clear();
    }

    @Test void fullFlowCookieFixationCsrfAndLogout() throws Exception {
        var browser = new Browser();
        var csrf = browser.csrf();
        String anonymousCookie = browser.cookie();
        browser.request("POST", "/api/auth/register", Map.of("email", "one@example.com", "password", PASSWORD), csrf, 202);
        browser.request("POST", "/api/auth/verify-email", Map.of("token", mail.last().token()), csrf, 204);
        var login = browser.request("POST", "/api/auth/login", Map.of("email", "one@example.com", "password", PASSWORD), csrf, 200);
        assertThat(login.body()).doesNotContain("authVersion", "token", PASSWORD);
        assertThat(browser.cookie()).isNotEqualTo(anonymousCookie);
        assertThat(login.headers().allValues("Set-Cookie").toString()).contains("HttpOnly", "SameSite=Lax", "Path=/").doesNotContain("Secure");
        browser.request("GET", "/api/auth/me", null, null, 200);
        var rejected = browser.request("POST", "/api/tasks", Map.of("title", "Task"), csrf, 403);
        assertThat(json.readTree(rejected.body()).get("code").asString()).isEqualTo("csrf_invalid");
        csrf = browser.csrf();
        var created = browser.request("POST", "/api/tasks", Map.of("title", "  Task  "), csrf, 201);
        var task = json.readTree(created.body());
        String path = "/api/tasks/" + task.get("id").asString();
        assertThat(created.headers().firstValue("Location")).contains(path);
        assertThat(task.get("title").asString()).isEqualTo("Task");
        assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
        browser.request("PATCH", path, Map.of("title", "Renamed"), csrf, 200);
        browser.request("PATCH", path, Map.of("completed", true), csrf, 200);
        var updated = json.readTree(browser.request("GET", path, null, null, 200).body());
        assertThat(updated.get("title").asString()).isEqualTo("Renamed");
        assertThat(updated.get("completed").asBoolean()).isTrue();
        assertThat(updated.get("createdAt")).isEqualTo(task.get("createdAt"));
        browser.request("PATCH", path, Map.of("title", "Both", "completed", false), csrf, 200);
        browser.request("DELETE", path, null, csrf, 204);
        browser.request("GET", path, null, null, 404);
        browser.request("POST", "/api/auth/logout", null, csrf, 204);
        browser.request("GET", "/api/auth/me", null, null, 401);
    }

    @Test void patchBindingRejectsEmptyNullUnknownAndWrongTypesWithMockMvc() throws Exception {
        var browser = verified("one@example.com");
        String csrf = browser.csrf();
        String id = json.readTree(browser.request("POST", "/api/tasks", Map.of("title", "Task"), csrf, 201).body()).get("id").asString();
        for (String body : List.of("{}", "{\"title\":null}", "{\"completed\":null}",
                "{\"completed\":\"true\"}", "{\"completed\":1}", "{\"title\":123}",
                "{\"title\":\"   \"}", "{\"userId\":\"foreign\"}", "{\"title\":\"ok\",\"unknown\":true}")) {
            mvc.perform(patch("/api/tasks/" + id).cookie(new Cookie("JSESSIONID", browser.cookie()))
                            .header("X-CSRF-TOKEN", csrf).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("validation_failed"));
        }
        browser.request("PATCH", "/api/tasks/" + id, Map.of("title", "😀".repeat(200)), csrf, 200);
        browser.request("PATCH", "/api/tasks/" + id, Map.of("title", "😀".repeat(201)), csrf, 400);
        browser.request("GET", "/api/tasks/invalid-uuid", null, null, 400);
    }

    @Test void twoUsersAreIsolatedAndLogoutOnlyEndsOneSession() throws Exception {
        var first = verified("first@example.com");
        var second = verified("second@example.com");
        var sibling = new Browser(); sibling.csrf(); sibling.login("first@example.com", PASSWORD);
        String csrf = first.csrf();
        String id = json.readTree(first.request("POST", "/api/tasks", Map.of("title", "Private"), csrf, 201).body()).get("id").asString();
        second.request("GET", "/api/tasks/" + id, null, null, 404);
        second.request("PATCH", "/api/tasks/" + id, Map.of("title", "Steal"), second.csrf(), 404);
        second.request("DELETE", "/api/tasks/" + id, null, second.csrf(), 404);
        first.request("POST", "/api/auth/logout", null, csrf, 204);
        first.request("GET", "/api/auth/me", null, null, 401);
        sibling.request("GET", "/api/auth/me", null, null, 200);
        second.request("GET", "/api/auth/me", null, null, 200);
    }

    @Test void resetFromOwnSessionRevokesAllOldSessionsAndAllowsNewLogin() throws Exception {
        var own = verified("one@example.com");
        var sibling = new Browser(); sibling.csrf(); sibling.login("one@example.com", PASSWORD);
        String csrf = own.csrf();
        own.request("POST", "/api/auth/forgot-password", Map.of("email", "one@example.com"), csrf, 202);
        own.request("POST", "/api/auth/reset-password", Map.of("token", mail.last().token(), "newPassword", NEW_PASSWORD), csrf, 204);
        own.request("GET", "/api/auth/me", null, null, 401);
        sibling.request("GET", "/api/auth/me", null, null, 401);
        own.csrf(); own.login("one@example.com", NEW_PASSWORD);
        own.request("GET", "/api/auth/me", null, null, 200);
    }

    @Test void resetFromOtherUserAndAnonymousPreservesUnrelatedSession() throws Exception {
        var target = verified("target@example.com");
        var other = verified("other@example.com");
        other.request("POST", "/api/auth/forgot-password", Map.of("email", "target@example.com"), other.csrf(), 202);
        other.request("POST", "/api/auth/reset-password", Map.of("token", mail.last().token(), "newPassword", NEW_PASSWORD), other.csrf(), 204);
        other.request("GET", "/api/auth/me", null, null, 200);
        target.request("GET", "/api/auth/me", null, null, 401);
        var anonymous = new Browser();
        anonymous.request("POST", "/api/auth/forgot-password", Map.of("email", "target@example.com"), anonymous.csrf(), 202);
        anonymous.request("POST", "/api/auth/reset-password", Map.of("token", mail.last().token(), "newPassword", PASSWORD), anonymous.csrf(), 204);
        anonymous.request("GET", "/api/auth/me", null, null, 401);
        other.request("GET", "/api/auth/me", null, null, 200);
    }

    @Test void staleVersionGuardWorksEvenIfSessionWasNotPhysicallyDeleted() throws Exception {
        var browser = verified("one@example.com");
        jdbc.update("UPDATE users SET auth_version=auth_version+1 WHERE email='one@example.com'");
        browser.request("GET", "/api/auth/me", null, null, 401);
    }

    @Test void sessionRoundTripStoresOnlyLocalIdentity() throws Exception {
        var browser = verified("one@example.com");
        var user = json.readTree(browser.request("GET", "/api/auth/me", null, null, 200).body());
        var found = sessions.findByPrincipalName(user.get("id").asString());
        assertThat(found).hasSize(1);
        var session = found.values().iterator().next();
        SessionIdentity identity = session.getAttribute(SessionIdentity.ATTRIBUTE);
        assertThat(identity.userId().toString()).isEqualTo(user.get("id").asString());
        assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
        var context = (org.springframework.security.core.context.SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication().getPrincipal()).isEqualTo(identity);
        assertThat(context.getAuthentication().getCredentials()).isNull();
    }

    @Test void concurrentPartialUpdatesPreserveTheOtherField() throws Exception {
        var browser = verified("one@example.com");
        String csrf = browser.csrf();
        String path = browser.request("POST", "/api/tasks", Map.of("title", "Before"), csrf, 201).headers().firstValue("Location").orElseThrow();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> browser.request("PATCH", path, Map.of("title", "After"), csrf, 200));
            var second = executor.submit(() -> browser.request("PATCH", path, Map.of("completed", true), csrf, 200));
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        }
        var task = json.readTree(browser.request("GET", path, null, null, 200).body());
        assertThat(task.get("title").asString()).isEqualTo("After");
        assertThat(task.get("completed").asBoolean()).isTrue();
    }

    @Test void unauthenticatedAndCsrfErrorsAreProblemsAndGoogleIsOptional() throws Exception {
        var browser = new Browser();
        var unauth = browser.request("GET", "/api/tasks", null, null, 401);
        assertThat(json.readTree(unauth.body()).get("code").asString()).isEqualTo("unauthenticated");
        var noCsrf = browser.request("POST", "/api/auth/register", Map.of("email", "one@example.com", "password", PASSWORD), null, 403);
        assertThat(json.readTree(noCsrf.body()).get("code").asString()).isEqualTo("csrf_invalid");
        var google = browser.request("GET", "/oauth2/authorization/google", null, null, 503);
        assertThat(json.readTree(google.body()).get("code").asString()).isEqualTo("google_unavailable");
        assertThat(browser.request("GET", "/login/oauth2/code/google?error=denied", null, null, 302).headers().firstValue("Location"))
                .contains("/login?error=oauth_failed");
        var health = json.readTree(browser.request("GET", "/actuator/health", null, null, 200).body());
        assertThat(health.size()).as(health.toString()).isEqualTo(1); assertThat(health.get("status").asString()).isEqualTo("UP");
    }

    Browser verified(String email) throws Exception {
        var b = new Browser(); String csrf = b.csrf();
        b.request("POST", "/api/auth/register", Map.of("email", email, "password", PASSWORD), csrf, 202);
        b.request("POST", "/api/auth/verify-email", Map.of("token", mail.last().token()), csrf, 204);
        b.login(email, PASSWORD); return b;
    }

    class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> request(String method, String path, Object body, String csrf, int status) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20));
            if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
            if (body != null) builder.header("Content-Type", "application/json");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(status);
            return response;
        }
        String csrf() throws Exception { return json.readTree(request("GET", "/api/auth/csrf", null, null, 200).body()).get("token").asString(); }
        void login(String email, String password) throws Exception { request("POST", "/api/auth/login", Map.of("email", email, "password", password), csrf(), 200); }
        String cookie() { return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MailConfiguration {
        @Bean Mailbox mailbox() { return new Mailbox(); }
    }
    static class Mailbox implements IdentityMailSender {
        final List<IdentityMail> messages = new CopyOnWriteArrayList<>();
        @Override public void send(IdentityMail message) { messages.add(message); }
        IdentityMail last() { return messages.getLast(); }
    }
}
