package com.poparthur.mynotes.identity.service.mail.resend;

import com.poparthur.mynotes.identity.service.mail.IdentityMailSender;
import com.poparthur.mynotes.identity.service.mail.smtp.IdentityMailTemplate;
import com.poparthur.mynotes.shared.service.PublicOrigin;
import jakarta.mail.internet.InternetAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
public class ResendConfiguration {
    @Bean
    @ConditionalOnProperty(name = "app.mail.provider", havingValue = "resend")
    IdentityMailSender resendIdentityMailSender(
            @Value("${app.mail.resend-api-key:}") String apiKey,
            @Value("${app.mail.resend-from:}") String from,
            PublicOrigin origin) {
        origin.require();
        if (apiKey.isBlank() || !apiKey.startsWith("re_") || apiKey.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("RESEND_API_KEY is required and must be valid");
        }
        try {
            if (from.isBlank() || from.contains("\r") || from.contains("\n")) throw new IllegalArgumentException();
            var addresses = InternetAddress.parse(from, true);
            if (addresses.length != 1) throw new IllegalArgumentException();
            addresses[0].validate();
        } catch (Exception invalid) {
            throw new IllegalStateException("AUTH_FROM_EMAIL must contain one valid sender");
        }
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        var client = RestClient.builder().baseUrl("https://api.resend.com")
                .requestFactory(factory).defaultHeader("Authorization", "Bearer " + apiKey).build();
        return new ResendIdentityMailSender(client, from, new IdentityMailTemplate(origin));
    }
}
