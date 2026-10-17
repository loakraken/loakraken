-- 수집품(내실) 랭킹(#295, 볼트 「랭킹 확장 설계 초안」 v1): 수집품은 원정대가 함께 모으므로 원정대마다 한 벌을 둔다.
-- armory를 받을 때(사용자 검색·수집기 프로필 갱신) 그 원정대의 값을 공식 Point·MaxPoint 그대로 덮어쓴다. 공식 호출은 늘지 않는다.
-- source_character_id: 이 값을 읽은 캐릭터. 그 캐릭터가 지워지면(보유 기간 정리) 다음 관측까지 원정대 값은 남는다.
-- sort_order: 공식 응답의 종류 순서(화면 열 순서).
CREATE TABLE roster_collectible (
    roster_key          UUID         NOT NULL,
    type                VARCHAR(32)  NOT NULL,
    sort_order          SMALLINT     NOT NULL,
    point               INTEGER      NOT NULL,
    max_point           INTEGER      NOT NULL,
    observed_at         TIMESTAMPTZ  NOT NULL,
    source_character_id UUID,
    PRIMARY KEY (roster_key, type)
);

CREATE INDEX idx_roster_collectible_observed ON roster_collectible (observed_at);
