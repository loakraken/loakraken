-- 인기 검색 캐릭터(F-10). 조회수(F-8, 반복 포함)와 분리한 순위용 집계다.
-- 같은 클라이언트·같은 캐릭터는 KST 하루 1회만 센다. 중복 제거 키는 Valkey에만 있고 하루 뒤 만료된다. 원본 IP는 저장하지 않는다.
CREATE TABLE character_popular_hourly (
    character_name VARCHAR(64) NOT NULL,
    server_name VARCHAR(64) NOT NULL,
    hour_bucket TIMESTAMPTZ NOT NULL,
    unique_views INTEGER NOT NULL CHECK (unique_views > 0),
    PRIMARY KEY (character_name, server_name, hour_bucket)
);

CREATE INDEX idx_character_popular_hour ON character_popular_hourly (hour_bucket);

-- 공개 순위(인기 검색 목록·랭킹 목록)에서 뺄 캐릭터. 운영자가 수동으로 등록한다(docs/popular-characters.md). server_name이 NULL이면 모든 서버.
-- 본인 순위 조회(/characters/rank)에는 적용하지 않는다(PM 총괄 결정 2026-10-08).
CREATE TABLE public_exclusion (
    exclusion_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    character_name VARCHAR(64) NOT NULL,
    server_name VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason_code VARCHAR(32) NOT NULL
);

CREATE UNIQUE INDEX uq_public_exclusion ON public_exclusion (character_name, COALESCE(server_name, ''));
