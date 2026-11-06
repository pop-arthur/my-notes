CREATE TABLE users (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL,
    email_verified boolean NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email CHECK (email = lower(btrim(email)) AND email <> ''),
    CONSTRAINT ck_users_dates CHECK (updated_at >= created_at)
);

CREATE TABLE password_credentials (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    password_hash varchar(512) NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_password_hash CHECK (btrim(password_hash) <> '')
);

CREATE TABLE external_identities (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    issuer varchar(512) NOT NULL,
    subject varchar(255) NOT NULL,
    CONSTRAINT uq_external_identity UNIQUE (issuer, subject),
    CONSTRAINT ck_external_issuer CHECK (btrim(issuer) <> ''),
    CONSTRAINT ck_external_subject CHECK (btrim(subject) <> '')
);
CREATE INDEX ix_external_identity_user ON external_identities (user_id);

CREATE TABLE email_tokens (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    purpose varchar(32) NOT NULL,
    token_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    CONSTRAINT uq_email_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_email_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_email_token_purpose CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    CONSTRAINT ck_email_token_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_email_token_consumed CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);
CREATE INDEX ix_email_tokens_user_purpose ON email_tokens (user_id, purpose);

CREATE TABLE tasks (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    title varchar(200) NOT NULL,
    completed boolean NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_task_title CHECK (title ~ '[^[:space:]]' AND title = btrim(title)),
    CONSTRAINT ck_task_dates CHECK (updated_at >= created_at)
);
CREATE INDEX ix_tasks_owner_order ON tasks (user_id, created_at DESC, id DESC);
