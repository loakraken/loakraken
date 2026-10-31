-- 장착 휘장(공식 프로필 Decorations.Emblems, 아이콘 주소만 있고 이름은 없다). 캐릭터마다 마지막 관측의 휘장 집합이다.
-- 주소는 공식 이미지 서버 주소만, 쿼리·조각을 뺀 형태로 저장한다(통계의 묶음 기준). 이미지는 저장하지 않는다.
CREATE TABLE character_emblem (
    character_id UUID NOT NULL REFERENCES characters(character_id) ON DELETE CASCADE,
    icon_url VARCHAR(512) NOT NULL,
    PRIMARY KEY (character_id, icon_url)
);
CREATE INDEX idx_character_emblem_icon ON character_emblem (icon_url);

-- 휘장 정보를 마지막으로 관측한 시각. NULL이면 모른다(휘장 없음과 구분).
ALTER TABLE characters ADD COLUMN emblems_observed_at TIMESTAMPTZ;
