-- 통계 표본 수집(B-3·F-7). 원정대로 발견한 캐릭터는 전투력·포지션이 프로필 갱신 전까지 비어 있다.
ALTER TABLE characters ADD COLUMN position VARCHAR(16);
ALTER TABLE characters ADD COLUMN profile_refreshed_at TIMESTAMPTZ;
ALTER TABLE characters ADD COLUMN roster_scanned_at TIMESTAMPTZ;
ALTER TABLE characters ADD COLUMN discovered_at TIMESTAMPTZ;

-- 기존 행은 사용자 검색으로 프로필을 받은 캐릭터다.
UPDATE characters SET profile_refreshed_at = last_synced_at;

CREATE INDEX idx_characters_profile_due ON characters (profile_refreshed_at NULLS FIRST, item_level DESC);
CREATE INDEX idx_characters_roster_due ON characters (roster_scanned_at NULLS FIRST, item_level DESC);
