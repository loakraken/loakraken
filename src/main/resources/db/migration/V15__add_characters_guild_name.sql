-- 길드 랭킹(#301, 볼트 「랭킹 확장 설계 초안」 v1 3절): armory 프로필 GuildName을 캐릭터 행에 둔다(처리방침 반영은 PM 총괄 확인).
-- 프로필을 받을 때마다 덮어쓴다(길드를 나가면 공식 값이 비어 NULL이 된다).
ALTER TABLE characters ADD COLUMN guild_name VARCHAR(64);
CREATE INDEX idx_characters_guild ON characters (server_name, guild_name) WHERE guild_name IS NOT NULL;
