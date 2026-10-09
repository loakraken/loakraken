-- B9 보유 기간(#141): 마지막 관측 후 1년 동안 다시 관측되지 않은 캐릭터를 지우면 그 캐릭터의 기록도 함께 지운다.
-- 카드(V6)·장비 요약(V11)은 이미 CASCADE다. 스냅샷·관측 이력·칭호를 맞춘다. public_exclusion은 이름 기준이라 영향이 없다.
ALTER TABLE character_snapshot DROP CONSTRAINT character_snapshot_character_id_fkey,
    ADD CONSTRAINT character_snapshot_character_id_fkey
        FOREIGN KEY (character_id) REFERENCES characters(character_id) ON DELETE CASCADE;

ALTER TABLE character_observation DROP CONSTRAINT character_observation_character_id_fkey,
    ADD CONSTRAINT character_observation_character_id_fkey
        FOREIGN KEY (character_id) REFERENCES characters(character_id) ON DELETE CASCADE;

ALTER TABLE character_title_observation DROP CONSTRAINT character_title_observation_character_id_fkey,
    ADD CONSTRAINT character_title_observation_character_id_fkey
        FOREIGN KEY (character_id) REFERENCES characters(character_id) ON DELETE CASCADE;
