-- 통계·랭킹(B-3·F-7 2단계).
-- roster_key: 수집기가 원정대를 훑을 때 같은 계정 캐릭터에 같은 키를 준다(대표 캐릭터 필터).
-- gem_mean_level: 4티어 보석(겁화·작열·광휘) 평균 레벨. armory를 관측할 때 갱신한다.
ALTER TABLE characters ADD COLUMN roster_key UUID;
ALTER TABLE characters ADD COLUMN gem_mean_level NUMERIC(4, 2);

-- profile_checked_at: 수집기가 프로필을 마지막으로 시도한 시각(성공·못 찾음·해석 실패). 재선정 주기에만 쓴다.
-- profile_refreshed_at은 성공한 관측 시각만 남긴다. 통계 모집단의 "최근 확인"은 이 값이다(PM 검토 #100 P1).
ALTER TABLE characters ADD COLUMN profile_checked_at TIMESTAMPTZ;
UPDATE characters SET profile_checked_at = profile_refreshed_at;
DROP INDEX idx_characters_profile_due;
CREATE INDEX idx_characters_profile_due ON characters (profile_checked_at NULLS FIRST, item_level DESC);
CREATE INDEX idx_characters_roster_missing ON characters (item_level DESC) WHERE roster_key IS NULL;

CREATE INDEX idx_characters_roster ON characters (roster_key, server_name, item_level DESC)
    WHERE roster_key IS NOT NULL;

-- 통계 모집단은 프로필(전투력)을 확인한 캐릭터다.
CREATE INDEX idx_characters_stats ON characters (profile_refreshed_at, item_level)
    INCLUDE (server_name, class_name, combat_power, position)
    WHERE combat_power IS NOT NULL;
