-- 길드 탭(L36·K28·M10): armory 프로필 GuildMemberGrade(공식 문자열 그대로). guild_name과 같은 UPSERT에서 덮어쓴다(길드가 없으면 NULL).
ALTER TABLE characters ADD COLUMN guild_member_grade VARCHAR(32);

-- 한 길드의 길드원 조회(서버 + 길드 이름, 대소문자 무시).
CREATE INDEX idx_characters_guild_lower ON characters (server_name, lower(guild_name))
    WHERE guild_name IS NOT NULL;
