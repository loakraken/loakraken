-- 관측한 최고 전투력(L37 랭킹). 우리가 관측한 기록 중 가장 높은 값이고, 프로필을 기록할 때 GREATEST로 갱신한다.
ALTER TABLE characters ADD COLUMN best_combat_power NUMERIC(12, 2);

-- 지금까지의 관측 기록으로 채운다.
UPDATE characters c
SET best_combat_power = GREATEST(
    c.combat_power,
    (SELECT MAX(o.combat_power) FROM character_observation o WHERE o.character_id = c.character_id))
WHERE c.combat_power IS NOT NULL
   OR EXISTS (SELECT 1 FROM character_observation o WHERE o.character_id = c.character_id);
