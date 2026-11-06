package com.poparthur.mynotes.identity.repo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_credentials")
public class PasswordCredentialEntity {
    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "password_hash", nullable = false, length = 512)
    private String passwordHash;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PasswordCredentialEntity() {
    }

    public PasswordCredentialEntity(UUID userId, String passwordHash, Instant updatedAt) {
        this.userId = userId;
        this.passwordHash = passwordHash;
        this.updatedAt = updatedAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "PasswordCredentialEntity[userId=" + userId + ", sensitive data redacted]";
    }
}
