-- 전체 인구(통계 묶음 B5): 주마다 저장한 캐릭터 수 묶음. 조건 없이(기간·레벨 제한 없음) 아이템 레벨이 있는 저장 캐릭터를 세고 처리정지 목록은 뺀다.
-- server_name '' = 전체. dimension: SERVER(전체만)·CLASS·LEVEL. LEVEL bucket = 아이템 레벨 10 단위 하한(정수 문자열).
CREATE TABLE stats_population_snapshot (
  snapshot_date DATE NOT NULL,
  server_name TEXT NOT NULL,
  dimension TEXT NOT NULL,
  bucket TEXT NOT NULL,
  character_count BIGINT NOT NULL,
  PRIMARY KEY (snapshot_date, server_name, dimension, bucket)
);

-- 스냅샷마다 만들 때의 처리정지 목록 값(StopListRevisionSql). 지금 목록 값과 다르면 읽을 때 지금 목록으로 다시 만든다(처리정지는 바로 반영, #774와 같은 원칙).
CREATE TABLE stats_population_snapshot_meta (
  snapshot_date DATE PRIMARY KEY,
  exclusion_revision TEXT NOT NULL,
  built_at TIMESTAMPTZ NOT NULL
);
