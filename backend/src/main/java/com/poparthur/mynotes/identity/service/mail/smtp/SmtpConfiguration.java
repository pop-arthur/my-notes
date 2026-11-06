package com.poparthur.mynotes.identity.service.mail.smtp;

import com.poparthur.mynotes.identity.service.mail.IdentityMailSender;
import com.poparthur.mynotes.shared.service.PublicOrigin;
import java.util.Properties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
@EnableConfigurationProperties(SmtpSettings.class)
public class SmtpConfiguration {
    @Bean
    JavaMailSender smtpTransport(SmtpSettings settings, PublicOrigin origin, Environment environment) {
        boolean local = environment.acceptsProfiles(Profiles.of("local", "test"));
        origin.require();
        if (settings.host() == null || settings.host().isBlank() || settings.host().matches(".*[\\s/].*")) {
            throw new IllegalStateException("SMTP_HOST is required and must be a hostname");
        }
        if (settings.effectivePort() < 1 || settings.effectivePort() > 65535) {
            throw new IllegalStateException("SMTP_PORT is invalid");
        }
        boolean username = settings.username() != null && !settings.username().isBlank();
        boolean password = settings.password() != null && !settings.password().isBlank();
        if (username != password || (!local && !username)) {
            throw new IllegalStateException("SMTP_USERNAME and SMTP_PASSWORD must be configured together");
        }
        if (!local && settings.tlsMode() == SmtpSettings.TlsMode.NONE) {
            throw new IllegalStateException("Unencrypted SMTP is allowed only for local/test profiles");
        }
        try {
            SmtpIdentityMailSender.address(settings.from());
        } catch (jakarta.mail.MessagingException invalid) {
            throw new IllegalStateException("MAIL_FROM must be a single valid email address");
        }
        var transport = new JavaMailSenderImpl();
        transport.setHost(settings.host()); transport.setPort(settings.effectivePort());
        transport.setProtocol("smtp"); transport.setDefaultEncoding("UTF-8");
        if (username) { transport.setUsername(settings.username()); transport.setPassword(settings.password()); }
        var properties = new Properties();
        properties.setProperty("mail.smtp.auth", Boolean.toString(username));
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(settings.tlsMode() == SmtpSettings.TlsMode.STARTTLS));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(settings.tlsMode() == SmtpSettings.TlsMode.STARTTLS));
        properties.setProperty("mail.smtp.ssl.enable", Boolean.toString(settings.tlsMode() == SmtpSettings.TlsMode.TLS));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "5000");
        properties.setProperty("mail.smtp.writetimeout", "5000");
        properties.setProperty("mail.debug", "false");
        transport.setJavaMailProperties(properties);
        transport.getSession().setDebug(false);
        return transport;
    }

    @Bean
    IdentityMailSender smtpIdentityMailSender(JavaMailSender transport, SmtpSettings settings, PublicOrigin origin) {
        return new SmtpIdentityMailSender(transport, settings.from(), new IdentityMailTemplate(origin));
    }
}
