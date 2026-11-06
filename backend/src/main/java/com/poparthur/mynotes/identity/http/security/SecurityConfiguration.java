package com.poparthur.mynotes.identity.http.security;

import com.poparthur.mynotes.identity.service.CurrentUserService;
import com.poparthur.mynotes.shared.http.*;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    public CookieSerializer cookieSerializer(@Value("${app.session.cookie-secure:true}") boolean secure, Environment environment) {
        if (!secure && !environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalStateException("Insecure session cookies require local or test profile");
        }
        var cookies = new DefaultCookieSerializer();
        cookies.setCookieName("JSESSIONID"); cookies.setCookiePath("/");
        cookies.setUseHttpOnlyCookie(true); cookies.setUseSecureCookie(secure); cookies.setSameSite("Lax");
        return cookies;
    }

    @Bean
    CsrfTokenRepository csrfTokens() {
        var tokens = new HttpSessionCsrfTokenRepository();
        tokens.setHeaderName("X-CSRF-TOKEN");
        return tokens;
    }

    @Bean
    SecurityContextRepository securityContexts() {
        return new DelegatingSecurityContextRepository(new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrf)));
    }

    @Bean
    AuthenticationManager passwordAuthenticationManager(EmailAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ProblemWriter problems, RateLimitService limits,
                                  CurrentUserService users, SessionSupport sessions, CsrfTokenRepository csrf,
                                  SecurityContextRepository contexts, GoogleOidcUserService googleUsers,
                                  ObjectProvider<ClientRegistrationRepository> clients,
                                  @Value("${app.oauth.google.enabled:false}") boolean googleEnabled) throws Exception {
        http.formLogin(config -> config.disable()).httpBasic(config -> config.disable())
                .logout(config -> config.disable()).rememberMe(config -> config.disable())
                .requestCache(config -> config.disable()).cors(config -> config.disable())
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(config -> config.securityContextRepository(contexts).requireExplicitSave(true))
                .csrf(config -> config.csrfTokenRepository(csrf))
                .headers(config -> config.cacheControl(cache -> cache.disable()).addHeaderWriter(new StaticHeadersWriter("Cache-Control", "no-store")))
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, failure) -> problems.write(request, response, 401, "unauthenticated"))
                        .accessDeniedHandler((request, response, failure) -> problems.write(request, response, 403,
                                failure instanceof org.springframework.security.web.csrf.CsrfException ? "csrf_invalid" : "access_denied")))
                .authorizeHttpRequests(config -> config
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/tasks", "/api/tasks/**", "/api/auth/me", "/api/auth/logout").authenticated()
                        .requestMatchers("/api/auth/**", "/oauth2/authorization/google", "/login/oauth2/code/google", "/actuator/health", "/", "/login").permitAll()
                        .anyRequest().denyAll());
        http.addFilterBefore(new SessionVersionFilter(users, sessions, problems), CsrfFilter.class);
        http.addFilterBefore(new RateLimitFilter(limits, problems), SessionVersionFilter.class);
        if (googleEnabled) {
            http.oauth2Login(config -> config.loginPage("/login").clientRegistrationRepository(clients.getObject())
                    .securityContextRepository(new RequestAttributeSecurityContextRepository())
                    .authorizedClientRepository(new DiscardingAuthorizedClients())
                    .userInfoEndpoint(info -> info.oidcUserService(googleUsers))
                    .successHandler((request, response, authentication) -> {
                        var principal = (LocalOidcUser) authentication.getPrincipal();
                        sessions.establish(principal.identity(), request, response);
                        response.setStatus(302);
                        response.setHeader("Location", "/");
                    })
                    .failureHandler((request, response, failure) -> {
                        sessions.logout(request, response);
                        response.setStatus(302);
                        response.setHeader("Location", "/login?error=oauth_failed");
                    }));
        }
        return http.build();
    }

    /** No Google tokens are kept in memory services or persistent session attributes. */
    private static class DiscardingAuthorizedClients implements OAuth2AuthorizedClientRepository {
        @Override public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String id, Authentication principal,
                jakarta.servlet.http.HttpServletRequest request) { return null; }
        @Override public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
                jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {}
        @Override public void removeAuthorizedClient(String id, Authentication principal,
                jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {}
    }
}
