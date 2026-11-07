package com.poparthur.mynotes;

import com.poparthur.mynotes.identity.service.exception.MailDeliveryFailureException;
import com.poparthur.mynotes.identity.service.mail.*;
import com.poparthur.mynotes.identity.service.mail.resend.*;
import com.poparthur.mynotes.identity.service.mail.smtp.*;
import com.poparthur.mynotes.shared.service.PublicOrigin;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ResendUnitTest {
    final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ResendConfiguration.class, SmtpConfiguration.class, PublicOrigin.class)
            .withPropertyValues("app.mail.enabled=true", "app.mail.provider=resend",
                    "app.mail.resend-api-key=re_private-test-key", "app.mail.resend-from=My Notes <notes@example.com>",
                    "app.public-origin=https://notes.example.com");

    @Test void selectsOneSenderWithoutSmtpCredentials() {
        context.run(c -> assertThat(c).hasNotFailed().hasSingleBean(IdentityMailSender.class)
                .doesNotHaveBean(org.springframework.mail.javamail.JavaMailSender.class));
        context.withPropertyValues("app.mail.enabled=false")
                .run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(IdentityMailSender.class));
    }

    @Test void incompleteConfigurationFailsWithoutExposingSecrets() {
        for (String property : List.of("app.mail.resend-api-key=", "app.mail.resend-from=", "app.mail.resend-from=bad",
                "app.mail.resend-from=a@example.com,b@example.com", "app.public-origin=")) {
            context.withPropertyValues(property).run(c -> {
                assertThat(c).hasFailed();
                assertThat(org.assertj.core.util.Throwables.getStackTrace(c.getStartupFailure()))
                        .doesNotContain("re_private-test-key");
            });
        }
    }

    @Test void sendsBothTemplatesWithTrustedLinksAndSafeErrors() {
        var builder = RestClient.builder().baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer re_private-test-key");
        var server = MockRestServiceServer.bindTo(builder).build();
        var sender = new ResendIdentityMailSender(builder.build(), "My Notes <notes@example.com>",
                new IdentityMailTemplate(new PublicOrigin("https://notes.example.com", new MockEnvironment())));
        for (var purpose : MailPurpose.values()) {
            String path = purpose == MailPurpose.EMAIL_VERIFICATION ? "verify-email" : "reset-password";
            server.expect(requestTo("https://api.resend.com/emails"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("Authorization", "Bearer re_private-test-key"))
                    .andExpect(jsonPath("$.from").value("My Notes <notes@example.com>"))
                    .andExpect(jsonPath("$.to[0]").value("person@example.com"))
                    .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString(
                            "https://notes.example.com/" + path + "?token=private%2Btoken")))
                    .andExpect(jsonPath("$.html").value(org.hamcrest.Matchers.containsString("<html lang=\"ru\">")))
                    .andRespond(withSuccess("{\"id\":\"message-id\"}", MediaType.APPLICATION_JSON));
        }
        for (var purpose : MailPurpose.values()) sender.send(new IdentityMail("person@example.com", purpose, "private+token"));
        server.verify();
        server.reset();
        for (var status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.FOUND)) {
            server.expect(requestTo("https://api.resend.com/emails"))
                    .andRespond(withStatus(status).body("private+token re_private-test-key"));
        }
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> sender.send(new IdentityMail("person@example.com", MailPurpose.EMAIL_VERIFICATION, "private+token")))
                    .isInstanceOf(MailDeliveryFailureException.class).hasNoCause()
                    .hasMessageNotContaining("private+token").hasMessageNotContaining("re_private-test-key");
        }
        server.verify();
    }
}
