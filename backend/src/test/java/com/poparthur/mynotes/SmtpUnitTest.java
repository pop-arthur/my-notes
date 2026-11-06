package com.poparthur.mynotes;

import com.poparthur.mynotes.identity.http.security.GoogleClientConfiguration;
import com.poparthur.mynotes.identity.service.exception.MailDeliveryFailureException;
import com.poparthur.mynotes.identity.service.mail.*;
import com.poparthur.mynotes.identity.service.mail.smtp.*;
import com.poparthur.mynotes.shared.service.PublicOrigin;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmtpUnitTest {
    final ApplicationContextRunner smtp = new ApplicationContextRunner()
            .withUserConfiguration(SmtpConfiguration.class, PublicOrigin.class)
            .withPropertyValues("app.mail.enabled=true", "app.mail.host=smtp.provider.test", "app.mail.from=noreply@example.com",
                    "app.mail.username=user", "app.mail.password=private-password", "app.public-origin=https://todo.example.com");
    final ApplicationContextRunner google = new ApplicationContextRunner()
            .withUserConfiguration(GoogleClientConfiguration.class, PublicOrigin.class)
            .withPropertyValues("app.oauth.google.enabled=true", "app.oauth.google.client-id=client", "app.oauth.google.client-secret=private-secret");

    @Test void starttlsDefaultsRequireEncryptionAndFiniteTimeouts() {
        smtp.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(IdentityMailSender.class);
            var transport = (JavaMailSenderImpl) context.getBean(JavaMailSender.class);
            assertThat(transport.getPort()).isEqualTo(587);
            assertThat(transport.getDefaultEncoding()).isEqualTo("UTF-8");
            var p = transport.getJavaMailProperties();
            assertThat(p).containsEntry("mail.smtp.starttls.enable", "true").containsEntry("mail.smtp.starttls.required", "true")
                    .containsEntry("mail.smtp.ssl.enable", "false").containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                    .containsEntry("mail.smtp.connectiontimeout", "5000").containsEntry("mail.smtp.timeout", "5000")
                    .containsEntry("mail.smtp.writetimeout", "5000").containsEntry("mail.smtp.auth", "true");
            assertThat(p).doesNotContainKey("mail.smtp.ssl.trust");
            assertThat(transport.getSession().getDebug()).isFalse();
            assertThat(context.getBean(SmtpSettings.class).toString()).doesNotContain("private-password");
        });
    }

    @Test void implicitTlsUses465AndNoStarttlsDowngrade() {
        smtp.withPropertyValues("app.mail.tls-mode=tls").run(context -> {
            assertThat(context).hasNotFailed();
            var transport = (JavaMailSenderImpl) context.getBean(JavaMailSender.class);
            assertThat(transport.getPort()).isEqualTo(465);
            assertThat(transport.getJavaMailProperties()).containsEntry("mail.smtp.ssl.enable", "true")
                    .containsEntry("mail.smtp.starttls.enable", "false").containsEntry("mail.smtp.ssl.checkserveridentity", "true");
        });
    }

    @Test void incompleteOrUnsafeProductionSettingsStopStartup() {
        for (String property : List.of("app.mail.host=", "app.mail.host=bad host", "app.mail.port=65536", "app.mail.from=bad",
                "app.mail.from=a@example.com,b@example.com", "app.mail.username=", "app.mail.password=",
                "app.mail.tls-mode=none", "app.public-origin=", "app.public-origin=http://todo.example.com")) {
            smtp.withPropertyValues(property).run(context -> assertThat(context).as(property).hasFailed());
        }
    }

    @Test void disabledMailHasNoSenderAndLocalMailpitNeedsNoAuthentication() {
        smtp.withPropertyValues("app.mail.enabled=false").run(context -> assertThat(context).doesNotHaveBean(IdentityMailSender.class));
        smtp.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues("app.mail.tls-mode=none", "app.mail.username=", "app.mail.password=", "app.public-origin=http://localhost:3000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var transport = (JavaMailSenderImpl) context.getBean(JavaMailSender.class);
                    assertThat(transport.getPort()).isEqualTo(1025);
                    assertThat(transport.getJavaMailProperties()).containsEntry("mail.smtp.auth", "false")
                            .containsEntry("mail.smtp.ssl.enable", "false").containsEntry("mail.smtp.starttls.enable", "false");
                });
    }

    @Test void originRejectsCredentialsAndArbitraryPathsOrQueries() {
        for (String value : List.of("https://user:secret@todo.example.com", "https://todo.example.com/path",
                "https://todo.example.com?redirect=evil", "https://todo.example.com#fragment", "//todo.example.com",
                "https://todo.example.com:65536", "https://todo.example.com:0")) {
            assertThatThrownBy(() -> new PublicOrigin(value, new MockEnvironment())).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("secret");
        }
        assertThat(new PublicOrigin("https://todo.example.com/", new MockEnvironment()).require()).isEqualTo(URI.create("https://todo.example.com"));
    }

    @Test void linksAndTemplatesEncodeTokensAndUseOnlyTheConfiguredOrigin() {
        var template = new IdentityMailTemplate(new PublicOrigin("https://todo.example.com", new MockEnvironment()));
        String token = "a+b &<\"/😀";
        var verification = template.render(new IdentityMail("recipient@example.com", MailPurpose.EMAIL_VERIFICATION, token));
        String link = verification.text().lines().skip(1).findFirst().orElseThrow();
        URI uri = URI.create(link);
        assertThat(uri.getHost()).isEqualTo("todo.example.com");
        assertThat(uri.getPath()).isEqualTo("/verify-email");
        assertThat(URLDecoder.decode(uri.getRawQuery().substring("token=".length()), StandardCharsets.UTF_8)).isEqualTo(token);
        assertThat(verification.html()).contains("Подтвердить email").doesNotContain("<\"/😀");
        assertThat(verification.toString()).doesNotContain(token);
        var reset = template.render(new IdentityMail("recipient@example.com", MailPurpose.PASSWORD_RESET, "token"));
        assertThat(reset.text()).contains("https://todo.example.com/reset-password?token=token");
        assertThat(reset.subject()).contains("Сброс пароля");
    }

    @Test void sendsUtf8MultipartAlternativeAndDoesNotExposeTransportFailures() throws Exception {
        var transport = mock(JavaMailSender.class);
        var message = new MimeMessage(Session.getInstance(new Properties()));
        when(transport.createMimeMessage()).thenReturn(message);
        var sender = new SmtpIdentityMailSender(transport, "noreply@example.com",
                new IdentityMailTemplate(new PublicOrigin("https://todo.example.com", new MockEnvironment())));
        var mail = new IdentityMail("recipient@example.com", MailPurpose.EMAIL_VERIFICATION, "secret-token");
        sender.send(mail); verify(transport).send(message); message.saveChanges();
        assertThat(message.getSubject()).contains("Подтвердите email");
        assertThat(message.getFrom()[0].toString()).isEqualTo("noreply@example.com");
        var textParts = new ArrayList<String>(); collect(message, textParts);
        assertThat(textParts).hasSize(2).allSatisfy(text -> assertThat(text).contains("Подтвердить email", "/verify-email?token=secret-token"));
        doThrow(new MailSendException("secret-token/private-password")).when(transport).send(message);
        assertThatThrownBy(() -> sender.send(mail)).isInstanceOf(MailDeliveryFailureException.class).hasNoCause()
                .hasMessageNotContaining("secret-token").hasMessageNotContaining("private-password");
    }

    @Test void googleDerivesCallbackOrValidatesTheExplicitValue() {
        google.withPropertyValues("app.public-origin=https://todo.example.com").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ClientRegistrationRepository.class).findByRegistrationId("google").getRedirectUri())
                    .isEqualTo("https://todo.example.com/login/oauth2/code/google");
        });
        google.withPropertyValues("app.oauth.google.redirect-uri=https://legacy.example.com/login/oauth2/code/google")
                .run(context -> assertThat(context).hasNotFailed());
        google.withPropertyValues("app.public-origin=https://todo.example.com",
                        "app.oauth.google.redirect-uri=https://other.example.com/login/oauth2/code/google")
                .run(context -> assertThat(context).hasFailed());
        google.run(context -> assertThat(context).hasFailed());
        google.withPropertyValues("app.public-origin=https://todo.example.com", "app.oauth.google.client-secret=")
                .run(context -> assertThat(context).hasFailed());
    }

    private void collect(Part part, List<String> texts) throws Exception {
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) collect(multipart.getBodyPart(i), texts);
        } else if (part.isMimeType("text/plain") || part.isMimeType("text/html")) {
            assertThat(part.getContentType().toLowerCase(Locale.ROOT)).contains("charset=utf-8");
            texts.add((String) part.getContent());
        }
    }
}
