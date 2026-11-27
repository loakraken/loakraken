-- 2부 대비 원본 형태 판정(계약 2026-11-28): 효율 값을 쓴 같은 조회에서 어긋난 HARD 규칙 id(쉼표로). 빈 문자열 = 오늘의 모양, NULL = 판정 전
-- 행(통과로 본다). 효율 랭킹은 읽을 때 막기 스위치가 켜져 있으면 판정이 있는 행을 뺀다. 채우기 없음, 메타데이터만 바뀐다.
ALTER TABLE characters ADD COLUMN shape_verdict TEXT;
