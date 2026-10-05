CREATE TABLE character_observation (
    observation_id UUID PRIMARY KEY,
    observation_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    character_id UUID NOT NULL REFERENCES characters(character_id),
    observed_at TIMESTAMPTZ NOT NULL,
    last_confirmed_at TIMESTAMPTZ NOT NULL,
    class_name VARCHAR(64) NOT NULL,
    character_level INTEGER CHECK (character_level >= 0),
    item_level NUMERIC NOT NULL CHECK (item_level >= 0),
    combat_power NUMERIC CHECK (combat_power >= 0),
    expedition_level INTEGER CHECK (expedition_level >= 0),
    CHECK (last_confirmed_at >= observed_at)
);

CREATE INDEX idx_character_observation_latest
    ON character_observation (character_id, observed_at DESC, observation_sequence DESC);
