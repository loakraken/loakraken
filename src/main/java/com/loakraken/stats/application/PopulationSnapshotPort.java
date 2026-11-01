package com.loakraken.stats.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 전체 인구 스냅샷(통계 묶음 B5) 저장소. */
public interface PopulationSnapshotPort {
  /** 가장 최근 스냅샷 날짜와 만들 때의 처리정지 목록 값. 없으면 빈 값. */
  Optional<Latest> latest();

  /** 지금 처리정지 목록 값(통계 캐시 키와 같은 값). */
  String exclusionRevision();

  /**
   * date 스냅샷을 지금 처리정지 목록으로 새로 만든다(그 날짜 행을 지우고 다시 셈, 한 트랜잭션, 인스턴스 사이 잠금). 같은 날짜가 이미 지금 목록 값으로 있으면
   * 아무것도 하지 않는다. keepFrom보다 오래된 스냅샷은 지운다.
   */
  void build(LocalDate date, LocalDate keepFrom);

  /** date 스냅샷에서 server('' = 전체)의 행. */
  List<Row> rows(LocalDate date, String server);

  record Latest(LocalDate date, String exclusionRevision) {}

  /** dimension = SERVER·CLASS·LEVEL. */
  record Row(String dimension, String bucket, long count) {}
}
