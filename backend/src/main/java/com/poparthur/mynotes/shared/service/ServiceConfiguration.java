package com.poparthur.mynotes.shared.service;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class ServiceConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    UuidGenerator uuidGenerator() {
        return UUID::randomUUID;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        String id = "pbkdf2@SpringSecurity_v5_8";
        return new DelegatingPasswordEncoder(id, Map.of(id,
                Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
}
