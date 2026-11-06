package com.poparthur.mynotes;

import com.poparthur.mynotes.identity.repo.PasswordCredentialRepository;
import com.poparthur.mynotes.identity.repo.UserRepository;
import com.poparthur.mynotes.identity.repo.entity.PasswordCredentialEntity;
import com.poparthur.mynotes.identity.repo.entity.UserEntity;
import com.poparthur.mynotes.identity.service.*;
import com.poparthur.mynotes.identity.service.exception.*;
import com.poparthur.mynotes.identity.service.mail.*;
import com.poparthur.mynotes.shared.service.*;
import com.poparthur.mynotes.shared.service.exception.ValidationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceUnitTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final InputValidation validation = new InputValidation(VALIDATORS.getValidator());

    @AfterAll
    static void closeValidators() {
        VALIDATORS.close();
    }

    @Test
    void emailIsCanonicalWithoutProviderSpecificChanges() {
        assertThat(validation.email("  A.B+tag@Example.COM\t")).isEqualTo("a.b+tag@example.com");
        for (String invalid : new String[]{"", "  ", "not-an-email", "a@", "a".repeat(255) + "@example.com"}) {
            assertThatThrownBy(() -> validation.email(invalid)).isInstanceOf(ValidationException.class);
        }
        assertThatThrownBy(() -> validation.email(null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void titleUsesUnicodeCodePointsAfterTrimming() {
        assertThat(validation.title(" \t Read \n")).isEqualTo("Read");
        assertThat(validation.title("😀".repeat(200))).hasSize(400);
        for (String invalid : new String[]{"", " \t\n", "😀".repeat(201), "a\0b"}) {
            assertThatThrownBy(() -> validation.title(invalid)).isInstanceOf(ValidationException.class);
        }
        assertThatThrownBy(() -> validation.title(null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void passwordIsCountedByCodePointsAndNeverTrimmedOrNormalized() {
        String unicode = "😀".repeat(128);
        assertThat(validation.newPassword(unicode, "password")).isSameAs(unicode);
        String spaces = "  long password  ";
        assertThat(validation.newPassword(spaces, "password")).isEqualTo(spaces);
        assertThat(validation.loginPassword("x")).isEqualTo("x");
        for (String invalid : new String[]{"x".repeat(11), "😀".repeat(129)}) {
            assertThatThrownBy(() -> validation.newPassword(invalid, "password")).isInstanceOf(ValidationException.class);
        }
        assertThatThrownBy(() -> validation.loginPassword("")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> validation.newPassword(null, "newPassword"))
                .isInstanceOf(ValidationException.class).hasMessage("Invalid input for field: newPassword");
    }

    @Test
    void timeIsUtcAndMicrosecondPrecision() {
        var clock = Clock.fixed(Instant.parse("2026-11-06T12:00:00.123456789Z"), ZoneOffset.UTC);
        assertThat(new ServiceTime(clock).now()).isEqualTo(Instant.parse("2026-11-06T12:00:00.123456Z"));
    }

    @Test
    void tokensHaveEntropySafeEncodingAndDeterministicHash() {
        var codec = new TokenCodec();
        String a = codec.generate();
        String b = codec.generate();
        assertThat(a).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(b);
        assertThat(codec.hash(a)).matches("[0-9a-f]{64}").isEqualTo(codec.hash(a)).isNotEqualTo(codec.hash(b));
        assertThat(codec.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThatThrownBy(() -> codec.hash(null)).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> codec.hash("")).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> codec.hash("x".repeat(513))).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void absentSenderFailsBeforeAnyAccountReadOrWrite() {
        var provider = new DefaultListableBeanFactory().getBeanProvider(IdentityMailSender.class);
        var delivery = new IdentityMailDelivery(provider);
        var users = mock(UserRepository.class);
        var credentials = mock(PasswordCredentialRepository.class);
        var tokens = mock(EmailTokenService.class);
        var tokenRepository = mock(com.poparthur.mynotes.identity.repo.EmailTokenRepository.class);
        var passwords = mock(PasswordEncoder.class);
        var time = mock(ServiceTime.class);
        var transaction = mock(NewTransaction.class);
        var registration = new RegistrationService(users, credentials, tokens, delivery, validation,
                passwords, time, UUID::randomUUID, transaction);
        var verification = new EmailVerificationService(users, credentials, tokenRepository, tokens,
                delivery, validation, time, transaction);
        var recovery = new PasswordRecoveryService(users, credentials, tokenRepository, tokens,
                delivery, validation, passwords, time, transaction);
        assertThatThrownBy(() -> registration.register("one@example.com", "long password"))
                .isInstanceOf(MailDeliveryUnavailableException.class);
        assertThatThrownBy(() -> verification.resend("one@example.com"))
                .isInstanceOf(MailDeliveryUnavailableException.class);
        assertThatThrownBy(() -> recovery.requestReset("one@example.com"))
                .isInstanceOf(MailDeliveryUnavailableException.class);
        verifyNoInteractions(users, credentials, tokens, tokenRepository, passwords, time, transaction);
    }

    @Test
    void mailAndFailuresNeverExposeTokenEvenThroughCause() {
        var message = new IdentityMail("one@example.com", MailPurpose.PASSWORD_RESET, "secret-raw-token");
        assertThat(message.toString()).doesNotContain(message.token());
        var sender = mock(IdentityMailSender.class);
        doThrow(new IllegalStateException("secret-raw-token")).when(sender).send(message);
        var delivery = new IdentityMailDelivery(new DefaultListableBeanFactory().getBeanProvider(IdentityMailSender.class));
        assertThatThrownBy(() -> delivery.send(sender, message)).isInstanceOf(MailDeliveryFailureException.class)
                .hasNoCause().hasMessageNotContaining("secret-raw-token");
    }

    @Test
    void missingAccountAndGoogleOnlyAccountStillVerifyDummyHash() {
        var users = mock(UserRepository.class);
        var credentials = mock(PasswordCredentialRepository.class);
        var encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("dummy-hash");
        var service = new PasswordAuthenticationService(users, credentials, validation, encoder, UUID::randomUUID);
        when(users.findByEmail("absent@example.com")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.authenticate("absent@example.com", "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
        var google = new UserEntity(UUID.randomUUID(), "google@example.com", true, Instant.EPOCH, Instant.EPOCH);
        when(users.findByEmail(google.getEmail())).thenReturn(Optional.of(google));
        when(credentials.findByUserId(google.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.authenticate(google.getEmail(), "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(encoder, times(2)).matches("wrong", "dummy-hash");
    }

    @Test
    void unverifiedEmailIsReportedOnlyAfterPasswordMatches() {
        var users = mock(UserRepository.class);
        var credentials = mock(PasswordCredentialRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var user = new UserEntity(UUID.randomUUID(), "one@example.com", false, Instant.EPOCH, Instant.EPOCH);
        when(encoder.encode(anyString())).thenReturn("dummy");
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(credentials.findByUserId(user.getId())).thenReturn(Optional.of(new PasswordCredentialEntity(user.getId(), "real-hash", Instant.EPOCH)));
        when(encoder.matches("correct", "real-hash")).thenReturn(true);
        var service = new PasswordAuthenticationService(users, credentials, validation, encoder, UUID::randomUUID);
        assertThatThrownBy(() -> service.authenticate(user.getEmail(), "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.authenticate(user.getEmail(), "correct"))
                .isInstanceOf(EmailNotVerifiedException.class);
    }
}
