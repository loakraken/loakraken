-- #369 캐릭터 이름 자동완성. 이름 앞부분 일치(LIKE 'q%')를 기본 콜레이션에서도 인덱스로 찾게 text_pattern_ops를 쓴다.
CREATE INDEX idx_characters_name_prefix ON characters (character_name text_pattern_ops);

-- 자동완성 초상. 검색·수집기 프로필 갱신 때 공식 프로필 이미지 주소를 넣는다(없으면 null).
ALTER TABLE characters ADD COLUMN image_url VARCHAR(512);
