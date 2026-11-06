package com.poparthur.mynotes;

import com.poparthur.mynotes.identity.repo.exception.*;
import com.poparthur.mynotes.identity.repo.*;
import com.poparthur.mynotes.identity.repo.entity.*;
import com.poparthur.mynotes.tasks.repo.exception.DuplicateTaskException;
import com.poparthur.mynotes.tasks.repo.TaskRepository;
import com.poparthur.mynotes.tasks.repo.entity.TaskEntity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static com.poparthur.mynotes.identity.repo.entity.TokenPurpose.*;
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"app.proxy.secret=","app.mail.enabled=false", "app.oauth.google.enabled=false", "app.public-origin="})
@Testcontainers
class RepositoryIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18").withStartupTimeout(java.time.Duration.ofSeconds(180));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00.123456Z");
    @Autowired UserRepository users;
    @Autowired TaskRepository tasks;
    @Autowired PasswordCredentialRepository credentials;
    @Autowired ExternalIdentityRepository identities;
    @Autowired EmailTokenRepository tokens;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE tasks, email_tokens, external_identities, password_credentials, users");
    }

    @Test
    void migrationIsRepeatableAndSchemaIsValidatedAtStartup() {
        assertThat(flyway.info().applied()).hasSize(3);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
    }

    @Test
    void userRoundTripAndConfirmation() {
        var user = user("one@example.com");
        assertThat(users.findById(user.getId())).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(user));
        assertThat(users.findByEmail(user.getEmail())).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(user));
        var later = NOW.plusSeconds(2);
        assertThat(users.confirmEmail(user.getId(), later)).isTrue();
        assertThat(users.findById(user.getId())).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(new UserEntity(user.getId(), user.getEmail(), true, NOW, later)));
        assertThat(users.confirmEmail(UUID.randomUUID(), later)).isFalse();
        assertThat(users.findByEmail("absent@example.com")).isEmpty();
    }

    @Test
    void duplicateEmailAndIdAreNamedErrorsAndNeverOverwrite() {
        var user = user("one@example.com");
        assertThatThrownBy(() -> users.create(new UserEntity(UUID.randomUUID(), user.getEmail(), true, NOW, NOW)))
                .isInstanceOf(DuplicateUserException.class);
        assertThatThrownBy(() -> users.create(new UserEntity(user.getId(), "other@example.com", true, NOW, NOW)))
                .isInstanceOf(DuplicateUserException.class);
        assertThat(users.findById(user.getId())).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(user));
    }

    @Test
    void duplicateManagedIdIsAlsoTranslatedAndTransactionRollsBack() {
        var user = new UserEntity(UUID.randomUUID(), "one@example.com", false, NOW, NOW);
        assertThatThrownBy(() -> inTransaction(() -> {
            users.create(user);
            return users.create(new UserEntity(user.getId(), "other@example.com", true, NOW, NOW));
        })).isInstanceOf(DuplicateUserException.class);
        assertThat(users.findById(user.getId())).isEmpty();
    }

    @Test
    void createRejectsAnAlreadyManagedInstance() {
        var user = new UserEntity(UUID.randomUUID(), "one@example.com", false, NOW, NOW);
        assertThatThrownBy(() -> inTransaction(() -> {
            users.create(user);
            return users.create(user);
        })).isInstanceOf(DuplicateUserException.class);
        assertThat(users.findById(user.getId())).isEmpty();
    }

    @Test
    void databaseRejectsUnnormalizedEmail() {
        assertThatThrownBy(() -> user(" Mixed@Example.com ")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isZero();
    }

    @Test
    void credentialsRoundTripAndUpdateNeverCreatesCredentials() {
        var local = user("local@example.com");
        var google = user("google@example.com");
        var credential = new PasswordCredentialEntity(local.getId(), "{bcrypt}hash", NOW);
        assertThat(credentials.create(credential)).usingRecursiveComparison().isEqualTo(credential);
        assertThat(credentials.findByUserId(local.getId())).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(credential));
        assertThat(credentials.updatePasswordHash(local.getId(), "{bcrypt}new", NOW.plusSeconds(1))).isTrue();
        assertThat(credentials.findByUserId(local.getId())).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(
                new PasswordCredentialEntity(local.getId(), "{bcrypt}new", NOW.plusSeconds(1))));
        assertThat(credentials.updatePasswordHash(google.getId(), "{bcrypt}new", NOW)).isFalse();
        assertThat(credentials.findByUserId(google.getId())).isEmpty();
        assertThatThrownBy(() -> credentials.create(credential)).isInstanceOf(DuplicatePasswordCredentialException.class);
    }

    @Test
    void externalIdentityRoundTripAndUniqueness() {
        var first = user("first@example.com");
        var second = user("second@example.com");
        var identity = new ExternalIdentityEntity(UUID.randomUUID(), first.getId(), "https://accounts.google.com", "subject-1");
        assertThat(identities.create(identity)).usingRecursiveComparison().isEqualTo(identity);
        assertThat(identities.findByIssuerAndSubject(identity.getIssuer(), identity.getSubject())).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(identity));
        assertThat(identities.findByIssuerAndSubject("other-issuer", identity.getSubject())).isEmpty();
        assertThatThrownBy(() -> identities.create(new ExternalIdentityEntity(UUID.randomUUID(), second.getId(), identity.getIssuer(), identity.getSubject())))
                .isInstanceOf(DuplicateExternalIdentityException.class);
        assertThatThrownBy(() -> identities.create(new ExternalIdentityEntity(identity.getId(), second.getId(), "other", "other")))
                .isInstanceOf(DuplicateExternalIdentityException.class);
        assertThat(credentials.findByUserId(first.getId())).isEmpty();
        assertThat(users.findById(second.getId())).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(second));
    }

    @Test
    void tasksRoundTripAndStableOwnerOrder() {
        var owner = user("owner@example.com");
        var other = user("other@example.com");
        var low = new TaskEntity(new UUID(0, 1), owner.getId(), "First", false, NOW, NOW);
        var high = new TaskEntity(new UUID(0, 2), owner.getId(), "Same timestamp", true, NOW, NOW);
        var latest = task(owner.getId(), NOW.plusSeconds(1));
        tasks.create(high);
        tasks.create(low);
        tasks.create(task(other.getId(), NOW.plusSeconds(2)));
        assertThat(tasks.create(latest)).usingRecursiveComparison().isEqualTo(latest);
        assertThat(tasks.findAllByUserId(owner.getId())).usingRecursiveComparison().isEqualTo(List.of(latest, high, low));
        assertThat(tasks.findByIdAndUserId(high.getId(), owner.getId())).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(high));
        assertThat(tasks.findAllByUserId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void tasksCannotBeReadUpdatedOrDeletedByOtherOwner() {
        var owner = user("owner@example.com");
        var other = user("other@example.com");
        var task = tasks.create(task(owner.getId(), NOW));
        assertThat(tasks.findByIdAndUserId(task.getId(), other.getId())).isEmpty();
        assertThat(tasks.setCompleted(task.getId(), other.getId(), true, NOW.plusSeconds(1))).isEmpty();
        assertThat(tasks.deleteByIdAndUserId(task.getId(), other.getId())).isFalse();
        assertThat(tasks.findByIdAndUserId(task.getId(), owner.getId())).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(task));
    }

    @Test
    void taskCompletionIsReversibleAndDeleteIsPermanent() {
        var owner = user("owner@example.com");
        var task = tasks.create(task(owner.getId(), NOW));
        var later = NOW.plusSeconds(1);
        assertThat(tasks.setCompleted(task.getId(), owner.getId(), true, later)).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(
                new TaskEntity(task.getId(), owner.getId(), task.getTitle(), true, NOW, later)));
        assertThat(tasks.setCompleted(task.getId(), owner.getId(), true, later)).get().extracting(TaskEntity::isCompleted).isEqualTo(true);
        assertThat(tasks.setCompleted(task.getId(), owner.getId(), false, later)).get().extracting(TaskEntity::isCompleted).isEqualTo(false);
        assertThat(tasks.deleteByIdAndUserId(task.getId(), owner.getId())).isTrue();
        assertThat(tasks.findByIdAndUserId(task.getId(), owner.getId())).isEmpty();
        assertThat(tasks.deleteByIdAndUserId(task.getId(), owner.getId())).isFalse();
        assertThat(tasks.setCompleted(task.getId(), owner.getId(), true, later)).isEmpty();
    }

    @Test
    void duplicateTaskIdNeverOverwritesExistingTask() {
        var owner = user("owner@example.com");
        var existing = tasks.create(task(owner.getId(), NOW));
        assertThatThrownBy(() -> tasks.create(new TaskEntity(existing.getId(), owner.getId(), "Changed", true, NOW, NOW)))
                .isInstanceOf(DuplicateTaskException.class);
        assertThat(tasks.findByIdAndUserId(existing.getId(), owner.getId())).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(existing));
    }

    @Test
    void databaseEnforcesTaskTitleAndForeignKeys() {
        var owner = user("owner@example.com");
        for (String title : List.of("", "   ", "\t\n", "x".repeat(201))) {
            assertThatThrownBy(() -> tasks.create(new TaskEntity(UUID.randomUUID(), owner.getId(), title, false, NOW, NOW)))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> tasks.create(task(UUID.randomUUID(), NOW))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> credentials.create(new PasswordCredentialEntity(UUID.randomUUID(), "hash", NOW)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> identities.create(new ExternalIdentityEntity(UUID.randomUUID(), UUID.randomUUID(), "issuer", "subject")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tokens.create(token(UUID.randomUUID(), PASSWORD_RESET, 'a', NOW.plusSeconds(60))))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void tokenRoundTripConsumptionAndUniqueness() {
        var owner = user("owner@example.com");
        var token = token(owner.getId(), EMAIL_VERIFICATION, 'a', NOW.plusSeconds(60));
        assertThat(tokens.create(token)).usingRecursiveComparison().isEqualTo(token);
        assertThatThrownBy(() -> tokens.create(new EmailTokenEntity(UUID.randomUUID(), owner.getId(), token.getPurpose(), token.getTokenHash(), NOW, token.getExpiresAt(), null)))
                .isInstanceOf(DuplicateEmailTokenException.class);
        assertThatThrownBy(() -> tokens.create(new EmailTokenEntity(token.getId(), owner.getId(), token.getPurpose(), "b".repeat(64), NOW, token.getExpiresAt(), null)))
                .isInstanceOf(DuplicateEmailTokenException.class);
        var consumed = inTransaction(() -> tokens.consume(token.getTokenHash(), token.getPurpose(), NOW.plusSeconds(1)));
        assertThat(consumed).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(new EmailTokenEntity(token.getId(), owner.getId(), token.getPurpose(), token.getTokenHash(), NOW, token.getExpiresAt(), NOW.plusSeconds(1))));
        assertThat(inTransaction(() -> tokens.consume(token.getTokenHash(), token.getPurpose(), NOW.plusSeconds(2)))).isEmpty();
    }

    @Test
    void tokenRejectsWrongPurposeUnknownHashAndExpiredBoundary() {
        var owner = user("owner@example.com");
        var token = tokens.create(token(owner.getId(), EMAIL_VERIFICATION, 'a', NOW.plusSeconds(60)));
        assertThat(inTransaction(() -> tokens.consume(token.getTokenHash(), PASSWORD_RESET, NOW))).isEmpty();
        assertThat(inTransaction(() -> tokens.consume("f".repeat(64), EMAIL_VERIFICATION, NOW))).isEmpty();
        assertThat(inTransaction(() -> tokens.consume(token.getTokenHash(), token.getPurpose(), token.getExpiresAt()))).isEmpty();
        assertThat(inTransaction(() -> tokens.consume(token.getTokenHash(), token.getPurpose(), token.getExpiresAt().plusSeconds(1)))).isEmpty();
    }

    @Test
    void revokeIsLimitedToUserAndPurpose() {
        var owner = user("owner@example.com");
        var other = user("other@example.com");
        tokens.create(token(owner.getId(), PASSWORD_RESET, 'a', NOW.plusSeconds(60)));
        tokens.create(token(owner.getId(), PASSWORD_RESET, 'b', NOW.plusSeconds(60)));
        tokens.create(token(owner.getId(), EMAIL_VERIFICATION, 'c', NOW.plusSeconds(60)));
        tokens.create(token(other.getId(), PASSWORD_RESET, 'd', NOW.plusSeconds(60)));
        assertThat(tokens.revokeByUserIdAndPurpose(owner.getId(), PASSWORD_RESET, NOW)).isEqualTo(2);
        assertThat(tokens.revokeByUserIdAndPurpose(owner.getId(), PASSWORD_RESET, NOW)).isZero();
        assertThat(inTransaction(() -> tokens.consume("a".repeat(64), PASSWORD_RESET, NOW))).isEmpty();
        assertThat(inTransaction(() -> tokens.consume("c".repeat(64), EMAIL_VERIFICATION, NOW))).isPresent();
        assertThat(inTransaction(() -> tokens.consume("d".repeat(64), PASSWORD_RESET, NOW))).isPresent();
    }

    @Test
    void databaseRejectsInvalidTokenHashPurposeAndDates() {
        var owner = user("owner@example.com");
        assertThatThrownBy(() -> tokens.create(new EmailTokenEntity(UUID.randomUUID(), owner.getId(), PASSWORD_RESET, "raw-token", NOW, NOW.plusSeconds(60), null)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tokens.create(token(owner.getId(), PASSWORD_RESET, 'a', NOW)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tokens.create(new EmailTokenEntity(UUID.randomUUID(), owner.getId(), PASSWORD_RESET, "b".repeat(64), NOW, NOW.plusSeconds(60), NOW.minusSeconds(1))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO email_tokens VALUES (?, ?, 'UNKNOWN', ?, ?, ?, NULL)",
                UUID.randomUUID(), owner.getId(), "c".repeat(64), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW.plusSeconds(60))))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void consumptionAndLockRequireCallingTransaction() {
        assertThatThrownBy(() -> users.findByIdForUpdate(UUID.randomUUID())).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tokens.consume("a".repeat(64), PASSWORD_RESET, NOW)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void compoundWritesAndConsumptionRollBackTogether() {
        var owner = user("owner@example.com");
        var token = tokens.create(token(owner.getId(), EMAIL_VERIFICATION, 'a', NOW.plusSeconds(60)));
        var task = task(owner.getId(), NOW);
        transaction.executeWithoutResult(status -> {
            assertThat(users.findByIdForUpdate(owner.getId())).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(owner));
            assertThat(tokens.consume(token.getTokenHash(), token.getPurpose(), NOW)).isPresent();
            users.confirmEmail(owner.getId(), NOW);
            tasks.create(task);
            status.setRollbackOnly();
        });
        assertThat(tasks.findByIdAndUserId(task.getId(), owner.getId())).isEmpty();
        assertThat(users.findById(owner.getId())).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(owner));
        assertThat(inTransaction(() -> tokens.consume(token.getTokenHash(), token.getPurpose(), NOW))).isPresent();
    }

    @Test
    void concurrentConsumptionHasExactlyOneWinner() throws Exception {
        var owner = user("owner@example.com");
        var token = tokens.create(token(owner.getId(), PASSWORD_RESET, 'a', NOW.plusSeconds(60)));
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> consume = () -> {
                start.await(10, TimeUnit.SECONDS);
                return inTransaction(() -> tokens.consume(token.getTokenHash(), token.getPurpose(), NOW).isPresent());
            };
            var a = executor.submit(consume);
            var b = executor.submit(consume);
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void userLockIsHeldUntilTransactionFinishes() throws Exception {
        var owner = user("owner@example.com");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> inTransaction(() -> {
                users.findByIdForUpdate(owner.getId());
                locked.countDown();
                await(release);
                return true;
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> inTransaction(() -> {
                    secondStarted.countDown();
                    return users.findByIdForUpdate(owner.getId());
                }));
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(second.get(10, TimeUnit.SECONDS)).hasValueSatisfying(actual -> assertThat(actual).usingRecursiveComparison().isEqualTo(owner));
            } finally {
                release.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void sensitiveEntitiesDoNotPrintHashes() {
        assertThat(new PasswordCredentialEntity(UUID.randomUUID(), "secret-password-hash", NOW).toString()).doesNotContain("secret-password-hash");
        assertThat(token(UUID.randomUUID(), PASSWORD_RESET, 'a', NOW.plusSeconds(1)).toString()).doesNotContain("a".repeat(64));
    }

    private UserEntity user(String email) {
        return users.create(new UserEntity(UUID.randomUUID(), email, false, NOW, NOW));
    }

    private TaskEntity task(UUID userId, Instant createdAt) {
        return new TaskEntity(UUID.randomUUID(), userId, "TaskEntity", false, createdAt, createdAt);
    }

    private EmailTokenEntity token(UUID userId, TokenPurpose purpose, char hash, Instant expiresAt) {
        return new EmailTokenEntity(UUID.randomUUID(), userId, purpose, String.valueOf(hash).repeat(64), NOW, expiresAt, null);
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
