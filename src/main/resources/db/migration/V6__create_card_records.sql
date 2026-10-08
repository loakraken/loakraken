-- 원정대 카드 기록(F-6). 공식 API에 카드 도감이 없어 장착 카드를 관측할 때마다 카드별 최고 각성을 남긴다.
CREATE TABLE character_card (
    character_id UUID NOT NULL REFERENCES characters (character_id) ON DELETE CASCADE,
    card_name VARCHAR(64) NOT NULL,
    grade VARCHAR(16),
    icon VARCHAR(512),
    awake_count SMALLINT NOT NULL CHECK (awake_count >= 0),
    awake_total SMALLINT,
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (character_id, card_name)
);

-- 세트 구성원과 효과 문구는 공식 응답의 CardSlots·효과 이름에서 학습한다.
CREATE TABLE card_set_member (
    set_name VARCHAR(64) NOT NULL,
    card_name VARCHAR(64) NOT NULL,
    first_observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (set_name, card_name)
);

CREATE INDEX idx_card_set_member_card ON card_set_member (card_name);

CREATE TABLE card_set_effect (
    set_name VARCHAR(64) NOT NULL,
    pieces SMALLINT NOT NULL CHECK (pieces > 0),
    awakening SMALLINT NOT NULL CHECK (awakening >= 0),
    description TEXT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (set_name, pieces, awakening)
);
