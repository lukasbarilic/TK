-- Nastavenia používateľa (TK-23, HLD kap. 9): každý používateľ si mení len vlastné.
CREATE TABLE user_preference (
    user_id    BIGINT       NOT NULL REFERENCES user_account (id),
    key        VARCHAR(100) NOT NULL,
    value      JSONB        NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key),
    CONSTRAINT user_preference_value_size CHECK (octet_length(value::text) <= 16384)
);
