ALTER TABLE users ADD COLUMN auth_version bigint NOT NULL DEFAULT 0;
ALTER TABLE users ADD CONSTRAINT ck_user_auth_version CHECK (auth_version >= 0);
