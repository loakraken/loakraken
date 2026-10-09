-- 장비 요약(#146, equipment-summary-v1). 원문은 저장하지 않고 armory를 받을 때마다 정규화한 특성만 남긴다.
-- 같은 특성·전투력이면 새 행 없이 last_confirmed_at만 바꾼다(구간 행). 캐릭터를 지우면 함께 지운다.
CREATE TABLE character_equipment_summary (
    summary_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    character_id UUID NOT NULL REFERENCES characters(character_id) ON DELETE CASCADE,
    observed_at TIMESTAMPTZ NOT NULL,
    last_confirmed_at TIMESTAMPTZ NOT NULL,
    schema_version VARCHAR(40) NOT NULL,
    combat_power NUMERIC(12, 2),
    item_level NUMERIC(8, 2),
    character_level INTEGER,
    class_name VARCHAR(32),
    ark_passive_title VARCHAR(64),
    features JSONB NOT NULL,
    features_hash CHAR(64) NOT NULL,
    CONSTRAINT chk_equipment_summary_confirmed CHECK (last_confirmed_at >= observed_at)
);

CREATE INDEX idx_equipment_summary_character
    ON character_equipment_summary (character_id, observed_at DESC);
