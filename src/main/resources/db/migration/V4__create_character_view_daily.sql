-- 캐릭터 조회수 일별 집계(F-8). 캐릭터명과 KST 날짜만 보관하며 조회한 사람의 정보는 저장하지 않는다.
CREATE TABLE character_view_daily (
    character_name VARCHAR(64) NOT NULL,
    view_date DATE NOT NULL,
    view_count INTEGER NOT NULL CHECK (view_count > 0),
    PRIMARY KEY (character_name, view_date)
);
