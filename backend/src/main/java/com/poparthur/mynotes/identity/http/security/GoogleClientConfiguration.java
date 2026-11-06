package com.poparthur.mynotes.identity.http.security;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

@Configuration(proxyBeanMethods = false)
public class GoogleClientConfiguration {
    @Bean
    @ConditionalOnProperty(name = "app.oauth.google.enabled", havingValue = "true")
    @ConditionalOnMissingBean(ClientRegistrationRepository.class)
    ClientRegistrationRepository googleClients(@Value("${app.oauth.google.client-id:}") String clientId,
                                               @Value("${app.oauth.google.client-secret:}") String secret,
                                               @Value("${app.oauth.google.redirect-uri:}") String redirect,
                                               Environment environment) {
        if (clientId.isBlank() || secret.isBlank() || redirect.isBlank()) {
            throw new IllegalStateException("Google OAuth requires client ID, secret and redirect URI");
        }
        URI uri = URI.create(redirect);
        boolean local = environment.acceptsProfiles(Profiles.of("local", "test"));
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !"/login/oauth2/code/google".equals(uri.getPath())
                || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) {
            throw new IllegalStateException("Invalid Google redirect URI");
        }
        var registration = CommonOAuth2Provider.GOOGLE.getBuilder("google").clientId(clientId)
                .clientSecret(secret).redirectUri(redirect).scope("openid", "email", "profile").build();
        return new InMemoryClientRegistrationRepository(registration);
    }
}
