package com.poparthur.mynotes;

import com.poparthur.mynotes.identity.repo.*;
import com.poparthur.mynotes.identity.repo.entity.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class SessionPersistenceIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18").withStartupTimeout(Duration.ofSeconds(180));

    @Test void jdbcSessionSurvivesApplicationContextRestart() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        try (var first = start()) {
            var now = Instant.now(); UUID id = UUID.randomUUID();
            first.getBean(UserRepository.class).create(new UserEntity(id, "one@example.com", true, now, now));
            first.getBean(PasswordCredentialRepository.class).create(new PasswordCredentialEntity(id,
                    first.getBean(PasswordEncoder.class).encode("long password"), now));
            String base = base(first);
            var csrf = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
            String token = JsonMapper.builder().build().readTree(csrf.body()).get("token").asString();
            var login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                    .header("X-CSRF-TOKEN", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"one@example.com\",\"password\":\"long password\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        }
        try (var restarted = start()) {
            var me = client.send(HttpRequest.newBuilder(URI.create(base(restarted) + "/api/auth/me")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
            assertThat(me.body()).contains("one@example.com").doesNotContain("authVersion");
        }
    }
    ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(MyNotesApplication.class).profiles("test").run(
                "--server.port=0", "--app.session.cookie-secure=false", "--app.oauth.google.enabled=false",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword());
    }
    String base(ConfigurableApplicationContext context) {
        return "http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    }
}
