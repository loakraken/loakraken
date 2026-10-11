-- 원정대 랭킹(#297, 볼트 「랭킹 확장 설계 초안」 v1 2절): armory 프로필 ExpeditionLevel을 캐릭터 행에 둔다.
-- 관측 이력(character_observation)에도 있지만 순위 집계는 characters 한 표만 읽는다. 프로필을 받을 때 갱신하고, 값이 없으면 이전 값을 둔다.
ALTER TABLE characters ADD COLUMN expedition_level INTEGER;
