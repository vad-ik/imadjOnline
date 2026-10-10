CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(64)  NOT NULL,
    avatar_key    VARCHAR(512),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE decks
    ADD COLUMN owner_id BIGINT REFERENCES users (id) ON DELETE SET NULL;

CREATE INDEX idx_decks_owner_id ON decks (owner_id);
