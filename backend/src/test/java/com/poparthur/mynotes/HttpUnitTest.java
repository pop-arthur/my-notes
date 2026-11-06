package com.poparthur.mynotes;

import com.poparthur.mynotes.identity.http.AuthController;
import com.poparthur.mynotes.identity.http.dto.*;
import com.poparthur.mynotes.identity.http.security.*;
import com.poparthur.mynotes.identity.service.*;
import com.poparthur.mynotes.identity.service.exception.*;
import com.poparthur.mynotes.identity.service.result.SessionIdentity;
import com.poparthur.mynotes.shared.http.*;
import com.poparthur.mynotes.shared.service.InputValidation;
import jakarta.servlet.FilterChain;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HttpUnitTest {
    @Test void mailFailuresAreSafe503ResponsesAndValidationIs400() throws Exception {
        var builder = JsonMapper.builder(); new StrictJsonConfiguration().strictJson().customize(builder);
        var json = builder.build();
        var registration = mock(RegistrationService.class);
        var validation = mock(InputValidation.class);
        doThrow(new MailDeliveryUnavailableException()).when(registration).register(anyString(), anyString());
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            var mvc = MockMvcBuilders.standaloneSetup(new AuthController(registration, mock(EmailVerificationService.class),
                            mock(PasswordRecoveryService.class), mock(CurrentUserService.class), mock(AuthenticationManager.class),
                            mock(SessionSupport.class), validation))
                    .setControllerAdvice(new ApiExceptionHandler(new ProblemWriter(json)))
                    .setMessageConverters(new JacksonJsonHttpMessageConverter(json)).setValidator(validator).build();
            mvc.perform(post("/api/auth/register").contentType("application/json").content("{\"email\":\"one@example.com\",\"password\":\"secret-password\"}"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("mail_delivery_unavailable"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-password"))));
            mvc.perform(post("/api/auth/register").contentType("application/json").content("{\"email\":\"one@example.com\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"));
        }
    }

    @Test void rateLimitFilterReturns429AndIgnoresForwardedHeaders() throws Exception {
        var service = new RateLimitService(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 100, 1, 1, 1, 1, 1);
        var filter = new RateLimitFilter(service, new ProblemWriter(JsonMapper.builder().build()));
        var first = new MockHttpServletRequest("POST", "/api/auth/login"); first.setRemoteAddr("127.0.0.1");
        first.addHeader("X-Forwarded-For", "one");
        var firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, (request, response) -> ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));
        assertThat(firstResponse.getStatus()).isEqualTo(204);
        var second = new MockHttpServletRequest("POST", "/api/auth/login"); second.setRemoteAddr("127.0.0.1");
        second.addHeader("X-Forwarded-For", "two");
        var blocked = new MockHttpServletResponse(); filter.doFilter(second, blocked, mock(FilterChain.class));
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
        assertThat(blocked.getContentAsString()).contains("rate_limited");
    }

    @Test void productionCookiesAreSecureAndInsecureRequiresAnExplicitLocalProfile() {
        var config = new SecurityConfiguration();
        var cookies = config.cookieSerializer(true, new MockEnvironment());
        var request = new MockHttpServletRequest(); request.setSecure(true);
        var response = new MockHttpServletResponse();
        cookies.writeCookieValue(new org.springframework.session.web.http.CookieSerializer.CookieValue(request, response, "opaque"));
        assertThat(response.getHeader("Set-Cookie")).contains("JSESSIONID=", "HttpOnly", "Secure", "SameSite=Lax", "Path=/");
        assertThatThrownBy(() -> config.cookieSerializer(false, new MockEnvironment())).isInstanceOf(IllegalStateException.class);
    }

    @Test void requestToStringDoesNotPrintSecrets() {
        assertThat(new LoginRequest("one@example.com", "secret").toString()).doesNotContain("secret");
        assertThat(new RegisterRequest("one@example.com", "secret").toString()).doesNotContain("secret");
        assertThat(new TokenRequest("secret").toString()).doesNotContain("secret");
        assertThat(new ResetPasswordRequest("secret", "password").toString()).doesNotContain("secret", "password");
    }
    @Test
    @SuppressWarnings("unchecked")
    void sessionCleanupKeepsNewVersionSessions() {
        var repository = mock(org.springframework.session.FindByIndexNameSessionRepository.class);
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("sessions", repository);
        var provider = (org.springframework.beans.factory.ObjectProvider) beans.getBeanProvider(org.springframework.session.FindByIndexNameSessionRepository.class);
        var service = new SessionRevocationService(provider);
        UUID userId = UUID.randomUUID();
        var old = new org.springframework.session.MapSession();
        old.setAttribute(SessionIdentity.ATTRIBUTE, new SessionIdentity(userId, 0));
        var fresh = new org.springframework.session.MapSession();
        fresh.setAttribute(SessionIdentity.ATTRIBUTE, new SessionIdentity(userId, 1));
        when(repository.findByPrincipalName(userId.toString())).thenReturn(java.util.Map.of(old.getId(), old, fresh.getId(), fresh));
        service.afterPasswordReset(new com.poparthur.mynotes.identity.service.event.PasswordResetCompleted(userId, 1));
        verify(repository).deleteById(old.getId());
        verify(repository, never()).deleteById(fresh.getId());
    }
}
