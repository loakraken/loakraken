-- 칭호 일별 스냅숏을 만들 때의 처리정지 목록(public_exclusion) 값. 목록이 바뀌면 지난 목록으로 만든 날짜는 주지 않고, 다음 만들기가 지운 뒤 오늘을 다시 만든다.
-- 이 열이 생기기 전 행은 '' 이라 지금 목록 값('0' 또는 md5 앞 12자리)과 달라 처음 만들 때 함께 지워진다. 추가만 하는 마이그레이션이다(기존 표 잠금은 열 추가 순간뿐).
ALTER TABLE title_equip_daily_meta ADD COLUMN exclusion_revision VARCHAR(64) NOT NULL DEFAULT '';
