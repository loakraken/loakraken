-- 칭호 장착 확인 일별 스냅숏(칭호 통계 날짜 비교). 우리 표(character_title_observation 등)로만 매일 한 번 만든다(공식 호출 없음).
-- 30일 창만, server_key = 서버 이름 또는 '' = 전체. 365일보다 오래된 행은 만드는 작업이 지운다. 추가만 하는 마이그레이션이다.
-- 날짜 조회·보관 삭제는 snapshot_date로 시작하는 기본 키가 받는다(날짜 인덱스를 따로 두지 않음).
CREATE TABLE title_equip_daily_meta (
    snapshot_date DATE NOT NULL,
    server_key VARCHAR(64) NOT NULL,
    population BIGINT NOT NULL CHECK (population >= 0),
    built_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (snapshot_date, server_key)
);

CREATE TABLE title_equip_daily (
    snapshot_date DATE NOT NULL,
    server_key VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    equipped_rosters BIGINT NOT NULL CHECK (equipped_rosters >= 0),
    observed_rosters BIGINT NOT NULL CHECK (observed_rosters >= 0),
    new_rosters_30d BIGINT NOT NULL CHECK (new_rosters_30d >= 0),
    observed_characters BIGINT NOT NULL CHECK (observed_characters >= 0),
    PRIMARY KEY (snapshot_date, server_key, title)
);
