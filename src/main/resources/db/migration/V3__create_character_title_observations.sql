CREATE TABLE character_title_observation (
    character_id UUID NOT NULL REFERENCES characters(character_id),
    title VARCHAR(128) NOT NULL,
    first_observed_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (character_id, title),
    CHECK (last_observed_at >= first_observed_at)
);
