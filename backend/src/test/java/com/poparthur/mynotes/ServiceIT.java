package com.poparthur.mynotes;

import com.poparthur.mynotes.identity.repo.*;
import com.poparthur.mynotes.identity.repo.entity.*;
import com.poparthur.mynotes.identity.repo.exception.DuplicateEmailTokenException;
import com.poparthur.mynotes.identity.service.*;
import com.poparthur.mynotes.identity.service.exception.*;
import com.poparthur.mynotes.identity.service.mail.*;
import com.poparthur.mynotes.shared.service.UuidGenerator;
import com.poparthur.mynotes.shared.service.exception.ValidationException;
import com.poparthur.mynotes.tasks.service.TaskService;
import com.poparthur.mynotes.tasks.service.exception.TaskNotFoundException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
@Import(ServiceIT.TestBeans.class)
class ServiceIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00.123456789Z");
    private static final String PASSWORD = "😀".repeat(12) + " very long password ";
    private static final String NEW_PASSWORD = "another unicode password 😀";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired RegistrationService registration;
    @Autowired EmailVerificationService verification;
    @Autowired PasswordAuthenticationService authentication;
    @Autowired PasswordRecoveryService recovery;
    @Autowired GoogleIdentityService google;
    @Autowired TaskService tasks;
    @Autowired UserRepository users;
    @Autowired PasswordCredentialRepository credentials;
    @Autowired EmailTokenRepository tokens;
    @Autowired PasswordEncoder passwords;
    @Autowired TokenCodec codec;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @Autowired MutableClock clock;
    @Autowired CapturingMailSender mail;
    @Autowired TestUuidGenerator ids;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE tasks, email_tokens, external_identities, password_credentials, users");
        ids.source = UUID::randomUUID;
        clock.set(NOW);
        mail.messages.clear();
        mail.fail = false;
    }

    @Test
    void registrationConfirmationAndUnicodePasswordAuthentication() {
        registration.register("  First+tag@Example.COM ", PASSWORD);
        var user = users.findByEmail("first+tag@example.com").orElseThrow();
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getCreatedAt()).isEqualTo(NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(user.getAuthVersion()).isZero();
        var credential = credentials.findByUserId(user.getId()).orElseThrow();
        assertThat(credential.getPasswordHash()).startsWith("{pbkdf2@SpringSecurity_v5_8}").doesNotContain(PASSWORD);
        assertThat(passwords.matches(PASSWORD, credential.getPasswordHash())).isTrue();
        assertThatThrownBy(() -> authentication.authenticate(user.getEmail(), "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> authentication.authenticate(user.getEmail(), PASSWORD))
                .isInstanceOf(EmailNotVerifiedException.class);
        assertThatThrownBy(() -> tasks.list(user.getId())).isInstanceOf(EmailNotVerifiedException.class);
        var message = mail.last();
        assertThat(message.purpose()).isEqualTo(MailPurpose.EMAIL_VERIFICATION);
        assertThat(message.token()).matches("[A-Za-z0-9_-]{43}");
        var token = tokens.findByHashAndPurpose(codec.hash(message.token()), TokenPurpose.EMAIL_VERIFICATION).orElseThrow();
        assertThat(token.getExpiresAt()).isEqualTo(token.getCreatedAt().plus(Duration.ofHours(24)));
        assertThat(token.getTokenHash()).isNotEqualTo(message.token());
        verification.verify(message.token());
        var result = authentication.authenticate(" First+TAG@example.com ", PASSWORD);
        assertThat(result.userId()).isEqualTo(user.getId());
        assertThat(result.authVersion()).isZero();
        assertThat(result.user().emailVerified()).isTrue();
        assertThat(result.toString()).doesNotContain(PASSWORD, credential.getPasswordHash(), message.token());
        assertThatThrownBy(() -> verification.verify(message.token())).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void duplicateRegistrationDoesNotChangeCredentialsOrRevealAccountType() {
        registration.register("one@example.com", PASSWORD);
        var user = users.findByEmail("one@example.com").orElseThrow();
        String original = credentials.findByUserId(user.getId()).orElseThrow().getPasswordHash();
        registration.register(" ONE@EXAMPLE.COM ", NEW_PASSWORD);
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().getPasswordHash()).isEqualTo(original);
        assertThat(mail.messages).hasSize(1);
        google.authenticate(googleIdentity("google@example.com", "subject"));
        registration.register("google@example.com", PASSWORD);
        assertThat(mail.messages).hasSize(1);
        assertThat(count("users")).isEqualTo(2);
        assertThat(count("password_credentials")).isEqualTo(1);
    }

    @Test
    void resendRevokesOldTokenAndSkipsIneligibleAddresses() {
        registration.register("one@example.com", PASSWORD);
        String old = mail.last().token();
        verification.resend("one@example.com");
        String replacement = mail.last().token();
        assertThat(replacement).isNotEqualTo(old);
        assertThatThrownBy(() -> verification.verify(old)).isInstanceOf(InvalidTokenException.class);
        verification.verify(replacement);
        verification.resend("one@example.com");
        verification.resend("absent@example.com");
        google.authenticate(googleIdentity("google@example.com", "subject"));
        verification.resend("google@example.com");
        assertThat(mail.messages).hasSize(2);
    }

    @Test
    void passwordResetChangesHashVersionAndRejectsOldPasswordAndToken() {
        var id = verifiedLocal("one@example.com");
        var before = authentication.authenticate("one@example.com", PASSWORD);
        recovery.requestReset("one@example.com");
        String old = mail.last().token();
        recovery.requestReset("one@example.com");
        String current = mail.last().token();
        var stored = tokens.findByHashAndPurpose(codec.hash(current), TokenPurpose.PASSWORD_RESET).orElseThrow();
        assertThat(stored.getExpiresAt()).isEqualTo(stored.getCreatedAt().plus(Duration.ofMinutes(30)));
        assertThatThrownBy(() -> recovery.reset(old, NEW_PASSWORD)).isInstanceOf(InvalidTokenException.class);
        recovery.reset(current, NEW_PASSWORD);
        var after = authentication.authenticate("one@example.com", NEW_PASSWORD);
        assertThat(after.userId()).isEqualTo(id);
        assertThat(after.authVersion()).isEqualTo(before.authVersion() + 1);
        assertThat(users.findById(id).orElseThrow().getAuthVersion()).isEqualTo(1);
        assertThatThrownBy(() -> authentication.authenticate("one@example.com", PASSWORD)).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> recovery.reset(current, PASSWORD)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void resetRequestsAreNeutralAndDoNotCreateGoogleCredentials() {
        recovery.requestReset("absent@example.com");
        registration.register("pending@example.com", PASSWORD);
        var googleUser = google.authenticate(googleIdentity("google@example.com", "subject"));
        int messages = mail.messages.size();
        recovery.requestReset("pending@example.com");
        recovery.requestReset("google@example.com");
        assertThat(mail.messages).hasSize(messages);
        assertThat(credentials.findByUserId(googleUser.userId())).isEmpty();
        assertThatThrownBy(() -> authentication.authenticate("google@example.com", PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void tokenPurposeExpiryAndUnknownTokensAreRejected() {
        registration.register("one@example.com", PASSWORD);
        String verificationToken = mail.last().token();
        assertThatThrownBy(() -> recovery.reset(verificationToken, NEW_PASSWORD)).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> verification.verify("unknown")).isInstanceOf(InvalidTokenException.class);
        clock.set(NOW.plus(Duration.ofHours(24)));
        assertThatThrownBy(() -> verification.verify(verificationToken)).isInstanceOf(InvalidTokenException.class);
        clock.set(NOW);
        verification.verify(verificationToken);
        recovery.requestReset("one@example.com");
        String resetToken = mail.last().token();
        clock.set(NOW.plus(Duration.ofMinutes(30)));
        assertThatThrownBy(() -> recovery.reset(resetToken, NEW_PASSWORD)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void failedRegistrationRollsBackEveryWriteAndSendsNoMail() {
        var owner = new UserEntity(UUID.randomUUID(), "existing@example.com", true,
                NOW.minusSeconds(1), NOW.minusSeconds(1));
        users.create(owner);
        UUID duplicateTokenId = UUID.randomUUID();
        tokens.create(new EmailTokenEntity(duplicateTokenId, owner.getId(), TokenPurpose.PASSWORD_RESET,
                "a".repeat(64), NOW.minusSeconds(1), NOW.plusSeconds(60), null));
        var sequence = List.of(UUID.randomUUID(), duplicateTokenId).iterator();
        ids.source = sequence::next;
        assertThatThrownBy(() -> registration.register("new@example.com", PASSWORD))
                .isInstanceOf(DuplicateEmailTokenException.class);
        assertThat(users.findByEmail("new@example.com")).isEmpty();
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("password_credentials")).isZero();
        assertThat(count("email_tokens")).isEqualTo(1);
        assertThat(mail.messages).isEmpty();
    }

    @Test
    void deliveryFailureHappensAfterCommitAndResendCanRecover() {
        mail.fail = true;
        assertThatThrownBy(() -> registration.register("one@example.com", PASSWORD))
                .isInstanceOf(MailDeliveryFailureException.class).hasNoCause();
        String failedToken = mail.last().token();
        assertThat(users.findByEmail("one@example.com")).isPresent();
        assertThat(count("password_credentials")).isEqualTo(1);
        mail.fail = false;
        verification.resend("one@example.com");
        assertThatThrownBy(() -> verification.verify(failedToken)).isInstanceOf(InvalidTokenException.class);
        verification.verify(mail.last().token());
    }

    @Test
    void verificationAndResetJoinCallerTransactionAndRollBackAtomically() {
        registration.register("one@example.com", PASSWORD);
        String token = mail.last().token();
        transaction.executeWithoutResult(status -> {
            verification.verify(token);
            status.setRollbackOnly();
        });
        assertThat(users.findByEmail("one@example.com").orElseThrow().isEmailVerified()).isFalse();
        verification.verify(token);
        recovery.requestReset("one@example.com");
        String reset = mail.last().token();
        transaction.executeWithoutResult(status -> {
            recovery.reset(reset, NEW_PASSWORD);
            status.setRollbackOnly();
        });
        assertThat(authentication.authenticate("one@example.com", PASSWORD).authVersion()).isZero();
        recovery.reset(reset, NEW_PASSWORD);
        assertThat(authentication.authenticate("one@example.com", NEW_PASSWORD).authVersion()).isEqualTo(1);
    }

    @Test
    void lockRefreshesAnAccountLoadedBeforeConcurrentConfirmation() {
        registration.register("one@example.com", PASSWORD);
        String token = mail.last().token();
        transaction.executeWithoutResult(status -> {
            var cached = users.findByEmail("one@example.com").orElseThrow();
            assertThat(cached.isEmailVerified()).isFalse();
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> verification.verify(token)).get(15, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            assertThat(users.findByIdForUpdate(cached.getId()).orElseThrow().isEmailVerified()).isTrue();
        });
    }

    @Test
    void failedGoogleIdentityInsertRollsBackTheNewUser() {
        google.authenticate(googleIdentity("existing@example.com", "existing"));
        UUID existingIdentityId = jdbc.queryForObject(
                "SELECT id FROM external_identities WHERE subject = 'existing'", UUID.class);
        var sequence = List.of(UUID.randomUUID(), existingIdentityId).iterator();
        ids.source = sequence::next;
        assertThatThrownBy(() -> google.authenticate(googleIdentity("new@example.com", "new-subject")))
                .isInstanceOf(GoogleAuthenticationException.class);
        assertThat(users.findByEmail("new@example.com")).isEmpty();
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("external_identities")).isEqualTo(1);
    }

    @Test
    void concurrentRegistrationIsNeutralAndCreatesOneCompleteAccount() throws Exception {
        raceAtUserIdGeneration();
        var outcomes = race(() -> { registration.register("one@example.com", PASSWORD); return true; });
        assertThat(outcomes).containsExactly(true, true);
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("password_credentials")).isEqualTo(1);
        assertThat(count("email_tokens")).isEqualTo(1);
        assertThat(mail.messages).hasSize(1);
    }

    @Test
    void concurrentGoogleFirstLoginResolvesTheWinningIdentity() throws Exception {
        raceAtUserIdGeneration();
        var outcomes = race(() -> google.authenticate(googleIdentity("google@example.com", "subject")));
        assertThat(outcomes.getFirst()).isEqualTo(outcomes.getLast());
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("external_identities")).isEqualTo(1);
        assertThat(count("password_credentials")).isZero();
    }

    @Test
    void concurrentVerificationHasOneWinner() throws Exception {
        registration.register("one@example.com", PASSWORD);
        String token = mail.last().token();
        var outcomes = race(() -> {
            try {
                verification.verify(token);
                return true;
            } catch (InvalidTokenException expected) {
                return false;
            }
        });
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void concurrentResendsLeaveExactlyOneUsableToken() throws Exception {
        registration.register("one@example.com", PASSWORD);
        race(() -> { verification.resend("one@example.com"); return true; });
        assertThat(mail.messages).hasSize(3);
        int valid = 0;
        for (var message : mail.messages) {
            try {
                verification.verify(message.token());
                valid++;
            } catch (InvalidTokenException expected) {
                // Replaced tokens are intentionally indistinguishable from invalid ones.
            }
        }
        assertThat(valid).isEqualTo(1);
    }

    @Test
    void concurrentResetAndReissueDoNotDeadlockOrLoseTheVersionIncrement() throws Exception {
        verifiedLocal("one@example.com");
        recovery.requestReset("one@example.com");
        String token = mail.last().token();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var reset = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    recovery.reset(token, NEW_PASSWORD);
                    return true;
                } catch (InvalidTokenException expected) {
                    return false;
                }
            });
            var resend = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                recovery.requestReset("one@example.com");
                return true;
            });
            boolean changed = reset.get(20, TimeUnit.SECONDS);
            assertThat(resend.get(20, TimeUnit.SECONDS)).isTrue();
            var result = authentication.authenticate("one@example.com", changed ? NEW_PASSWORD : PASSWORD);
            assertThat(result.authVersion()).isEqualTo(changed ? 1 : 0);
            recovery.reset(mail.last().token(), PASSWORD);
            assertThat(authentication.authenticate("one@example.com", PASSWORD).authVersion())
                    .isEqualTo(changed ? 2 : 1);
        }
    }

    @Test
    void googleIdentityReusesAccountWithoutOverwritingEmailOrAddingPassword() {
        var first = google.authenticate(googleIdentity("google@example.com", "subject"));
        var second = google.authenticate(googleIdentity("changed@example.com", "subject"));
        assertThat(second).isEqualTo(first);
        assertThat(users.findByEmail("changed@example.com")).isEmpty();
        assertThat(credentials.findByUserId(first.userId())).isEmpty();
        assertThatThrownBy(() -> google.authenticate(googleIdentity("google@example.com", "other-subject")))
                .isInstanceOf(GoogleAuthenticationException.class);
        verifiedLocal("local@example.com");
        assertThatThrownBy(() -> google.authenticate(googleIdentity("local@example.com", "new-subject")))
                .isInstanceOf(GoogleAuthenticationException.class);
        assertThat(count("users")).isEqualTo(2);
    }

    @Test
    void untrustedOrUnverifiedGoogleInputIsRejected() {
        assertThatThrownBy(() -> google.authenticate(new VerifiedGoogleIdentity("https://evil.example", "subject", "one@example.com", true)))
                .isInstanceOf(GoogleAuthenticationException.class);
        assertThatThrownBy(() -> google.authenticate(new VerifiedGoogleIdentity("https://accounts.google.com", "subject", "one@example.com", false)))
                .isInstanceOf(GoogleAuthenticationException.class);
        assertThatThrownBy(() -> google.authenticate(googleIdentity("invalid", "subject")))
                .isInstanceOf(GoogleAuthenticationException.class);
        assertThatThrownBy(() -> google.authenticate(null)).isInstanceOf(GoogleAuthenticationException.class);
        assertThat(count("users")).isZero();
    }

    @Test
    void tasksValidateInputAndEnforceOwnership() {
        UUID owner = verifiedLocal("one@example.com");
        UUID other = google.authenticate(googleIdentity("other@example.com", "subject")).userId();
        var first = tasks.create(owner, "  Read  ");
        assertThat(first.title()).isEqualTo("Read");
        assertThat(first.completed()).isFalse();
        clock.set(NOW.plusSeconds(1));
        var second = tasks.create(owner, "😀".repeat(200));
        assertThat(tasks.list(owner)).containsExactly(second, first);
        assertThat(tasks.list(other)).isEmpty();
        assertThat(tasks.get(owner, first.id())).isEqualTo(first);
        assertThat(tasks.setCompleted(owner, first.id(), true).completed()).isTrue();
        assertThat(tasks.setCompleted(owner, first.id(), true).completed()).isTrue();
        assertThat(tasks.setCompleted(owner, first.id(), false).completed()).isFalse();
        assertThatThrownBy(() -> tasks.get(other, first.id())).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> tasks.setCompleted(other, first.id(), true)).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> tasks.delete(other, first.id())).isInstanceOf(TaskNotFoundException.class);
        tasks.delete(owner, first.id());
        assertThatThrownBy(() -> tasks.delete(owner, first.id())).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> tasks.create(owner, " \t\n")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> tasks.list(UUID.randomUUID())).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> tasks.list(null)).isInstanceOf(InvalidCredentialsException.class);
    }

    private UUID verifiedLocal(String email) {
        registration.register(email, PASSWORD);
        verification.verify(mail.last().token());
        return users.findByEmail(email).orElseThrow().getId();
    }

    private VerifiedGoogleIdentity googleIdentity(String email, String subject) {
        return new VerifiedGoogleIdentity("https://accounts.google.com", subject, email, true);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void raceAtUserIdGeneration() {
        var barrier = new CyclicBarrier(2);
        var calls = new AtomicInteger();
        ids.source = () -> {
            if (calls.incrementAndGet() <= 2) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
            return UUID.randomUUID();
        };
    }

    private <T> List<T> race(Supplier<T> operation) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<T> work = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return operation.get();
            };
            var first = executor.submit(work);
            var second = executor.submit(work);
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        TestUuidGenerator testUuidGenerator() {
            return new TestUuidGenerator();
        }

        @Bean
        CapturingMailSender mailSender(DataSource source) {
            return new CapturingMailSender(source);
        }
    }

    static class TestUuidGenerator implements UuidGenerator {
        volatile Supplier<UUID> source = UUID::randomUUID;
        @Override public UUID next() { return source.get(); }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(NOW);
        void set(Instant value) { instant.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return instant.get(); }
    }

    static class CapturingMailSender implements IdentityMailSender {
        final List<IdentityMail> messages = new CopyOnWriteArrayList<>();
        volatile boolean fail;
        private final DataSource source;

        CapturingMailSender(DataSource source) { this.source = source; }

        @Override
        public void send(IdentityMail message) {
            // A separate connection must already see the token: delivery must never precede commit.
            try (var connection = source.getConnection();
                 var statement = connection.prepareStatement("SELECT count(*) FROM email_tokens WHERE token_hash = ?")) {
                statement.setString(1, new TokenCodec().hash(message.token()));
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (result.getInt(1) != 1) {
                        throw new IllegalStateException("Token was not committed before mail delivery");
                    }
                }
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException("Cannot inspect committed token");
            }
            messages.add(message);
            if (fail) {
                throw new IllegalStateException("Simulated mail failure: " + message.token());
            }
        }

        IdentityMail last() { return messages.getLast(); }
    }
}
