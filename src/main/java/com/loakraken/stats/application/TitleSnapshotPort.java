package com.loakraken.stats.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 칭호 장착 확인 일별 스냅숏 저장소. server key '' = 전체. */
public interface TitleSnapshotPort {
  /** 가장 최근 스냅숏 날짜(전체 기준). 없으면 빈 값. */
  Optional<LocalDate> latest();

  /**
   * date 스냅숏을 byServer(server key → 그날 30일 창 결과)로 바꿔 쓴다(그 날짜 행을 지우고 다시 넣음, 한 트랜잭션, 인스턴스 사이 잠금).
   * keepFrom보다 오래된 행은 지운다.
   */
  void replace(
      LocalDate date,
      Instant builtAt,
      Map<String, StatsPort.TitleEquip> byServer,
      LocalDate keepFrom);

  /** date·server key의 스냅숏. 그날 그 key의 메타 행이 없으면 빈 값(칭호 행이 없으면 빈 목록). */
  Optional<Snapshot> find(LocalDate date, String serverKey);

  /** server key의 스냅숏 날짜(최신순, limit개까지). */
  List<LocalDate> dates(String serverKey, int limit);

  record Snapshot(Instant builtAt, StatsPort.TitleEquip data) {}
}
