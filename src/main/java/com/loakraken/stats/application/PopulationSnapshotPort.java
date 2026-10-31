package com.loakraken.stats.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 전체 인구 스냅샷(통계 묶음 B5) 저장소. */
public interface PopulationSnapshotPort {
  /** 가장 최근 스냅샷 날짜. 없으면 빈 값. */
  Optional<LocalDate> latest();

  /** date 스냅샷을 새로 만든다(그 날짜 행을 지우고 다시 셈, 한 트랜잭션). keepFrom보다 오래된 스냅샷은 지운다. 처리정지 목록 캐릭터는 세지 않는다. */
  void build(LocalDate date, LocalDate keepFrom);

  /** date 스냅샷에서 server('' = 전체)의 행. */
  List<Row> rows(LocalDate date, String server);

  /** dimension = SERVER·CLASS·LEVEL. */
  record Row(String dimension, String bucket, long count) {}
}
