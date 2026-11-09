package com.loakraken.stats.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 칭호 장착 확인 일별 스냅숏 저장소. server key '' = 전체. 날짜마다 만들 때의 처리정지 목록 값(revision)을 함께 두고, 읽기는 지금 목록 값과 같은
 * 날짜만 준다(목록이 바뀐 뒤에는 그 캐릭터가 세어진 날짜를 주지 않음). 목록 값은 저장소가 같은 SQL·같은 스냅숏 안에서 읽는다.
 */
public interface TitleSnapshotPort {
  /** 지금 처리정지 목록 값으로 만든 가장 최근 스냅숏 날짜(전체 기준). 없으면 빈 값. */
  Optional<LocalDate> latest();

  /** 지금 처리정지 목록 값과 다른 값으로 만든 날짜가 남아 있는가. */
  boolean hasStale();

  /**
   * date 스냅숏을 새로 만든다. 한 REPEATABLE READ 트랜잭션 안에서 byServer(server key → 그날 30일 창 결과)를 계산하고, 같은 스냅숏의
   * 처리정지 목록 값을 읽고, 인스턴스 사이 잠금을 잡은 뒤 그 값과 다른 값으로 만든 날짜를 모두 지우고 그 날짜 행과 keepFrom보다 오래된 행을 지운 뒤 넣는다.
   * 그래서 저장한 목록 값은 언제나 집계가 쓴 목록과 같다(만드는 도중 목록이 바뀌었다가 돌아와도). 지운 지난 목록 날짜 수를 준다(0 = 목록이 그대로).
   */
  int replace(
      LocalDate date,
      Instant builtAt,
      Supplier<Map<String, StatsPort.TitleEquip>> byServer,
      LocalDate keepFrom);

  /** date·server key의 스냅숏(지금 목록 값으로 만든 것만, 한 스냅숏에서 메타·칭호 행을 읽음). 없으면 빈 값. */
  Optional<Snapshot> find(LocalDate date, String serverKey);

  /** server key의 스냅숏 날짜(지금 목록 값으로 만든 것만, 최신순, limit개까지). */
  List<LocalDate> dates(String serverKey, int limit);

  record Snapshot(Instant builtAt, StatsPort.TitleEquip data) {}
}
