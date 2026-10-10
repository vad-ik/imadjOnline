CREATE TABLE decks (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE cards (
    id          BIGSERIAL PRIMARY KEY,
    deck_id     BIGINT       NOT NULL REFERENCES decks (id) ON DELETE CASCADE,
    image_key   VARCHAR(512) NOT NULL,
    code        VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_cards_deck_id ON cards (deck_id);
