-- 장신구 연마 효과 줄(캐릭터마다 마지막 관측). 연마 수치표 근거 집계용: 부위·장신구 아이템 레벨·등급·효과·색(하/중/상)·값별로 서로 다른
-- 캐릭터 수를 센다(deploy/sql/polish-tally.sql). 같은 캐릭터를 여러 번 검색해도 늘지 않게 관측마다 그 캐릭터의 줄을 바꾼다.
CREATE TABLE character_polish_line (
    character_id UUID NOT NULL REFERENCES characters(character_id) ON DELETE CASCADE,
    line_no SMALLINT NOT NULL,
    slot VARCHAR(16) NOT NULL,
    item_level INTEGER,
    item_grade VARCHAR(16),
    option_name VARCHAR(64) NOT NULL,
    option_value VARCHAR(32) NOT NULL,
    colour VARCHAR(8),
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (character_id, line_no)
);
