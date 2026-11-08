package com.loakraken.stats.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 칭호 장착 확인 일별 스냅숏 저장소. server key '' = 전체. 날짜마다 만들 때의 처리정지 목록 값(revision)을 함께 두고, 읽기는 지금 목록 값과 같은
 * 날짜만 준다(목록이 바뀐 뒤에는 그 캐릭터가 세어진 날짜를 주지 않음).
 */
public interface TitleSnapshotPort {
  /** 지금 처리정지 목록 값으로 만든 가장 최근 스냅숏 날짜(전체 기준). 없으면 빈 값. */
  Optional<LocalDate> latest(String revision);

  /** 지금 처리정지 목록 값과 다른 값으로 만든 날짜가 남아 있는가. */
  boolean hasStale(String revision);

  /**
   * date 스냅숏을 byServer(server key → 그날 30일 창 결과)로 바꿔 쓴다. 한 트랜잭션·인스턴스 사이 잠금 안에서 revision과 다른 값으로 만든
   * 날짜를 모두 지우고, 그 날짜 행과 keepFrom보다 오래된 행을 지운 뒤 넣는다. 지운 지난 목록 날짜 수를 준다(0 = 목록이 그대로).
   */
  int replace(
      LocalDate date,
      Instant builtAt,
      Map<String, StatsPort.TitleEquip> byServer,
      LocalDate keepFrom,
      String revision);

  /** date·server key의 스냅숏(revision으로 만든 것만). 없으면 빈 값(칭호 행이 없으면 빈 목록). */
  Optional<Snapshot> find(LocalDate date, String serverKey, String revision);

  /** server key의 스냅숏 날짜(revision으로 만든 것만, 최신순, limit개까지). */
  List<LocalDate> dates(String serverKey, int limit, String revision);

  record Snapshot(Instant builtAt, StatsPort.TitleEquip data) {}
}
