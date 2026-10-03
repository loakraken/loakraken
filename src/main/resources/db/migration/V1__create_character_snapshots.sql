CREATE TABLE characters (
    character_id UUID PRIMARY KEY,
    character_name VARCHAR(64) NOT NULL,
    server_name VARCHAR(64) NOT NULL,
    class_name VARCHAR(64) NOT NULL,
    item_level NUMERIC NOT NULL CHECK (item_level >= 0),
    combat_power NUMERIC CHECK (combat_power >= 0),
    last_synced_at TIMESTAMPTZ NOT NULL,
    UNIQUE (server_name, character_name)
);

CREATE TABLE character_snapshot (
    snapshot_id UUID PRIMARY KEY,
    snapshot_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    character_id UUID NOT NULL REFERENCES characters(character_id),
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    armory_json JSONB NOT NULL CHECK (jsonb_typeof(armory_json) = 'object'),
    siblings_json JSONB NOT NULL CHECK (jsonb_typeof(siblings_json) = 'array'),
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_character_snapshot_latest
    ON character_snapshot (character_id, fetched_at DESC, snapshot_sequence DESC);
