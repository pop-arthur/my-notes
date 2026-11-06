package com.poparthur.mynotes.identity.repo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "external_identities")
public class ExternalIdentityEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "issuer", nullable = false, length = 512)
    private String issuer;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    protected ExternalIdentityEntity() {
    }

    public ExternalIdentityEntity(UUID id, UUID userId, String issuer, String subject) {
        this.id = id;
        this.userId = userId;
        this.issuer = issuer;
        this.subject = subject;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getSubject() {
        return subject;
    }
}
