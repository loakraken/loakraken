package com.loakraken.character.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 인기 검색 캐릭터(F-10) 저장소. 중복 제거 키와 결과 캐시는 Valkey, 시간별 고유 조회 수는 Postgres다. */
public interface CharacterPopularPort {
  /** KST 날짜별 집합에 넣는다. 처음 본 키면 true. 키는 하루 뒤 만료된다. */
  boolean markSeen(LocalDate day, String clientKey);

  void increment(String characterName, String serverName, Instant hourBucket);

  /** since 이후(포함) 시간 버킷 합계가 minUniqueViews 이상인 캐릭터. 제외 목록은 뺀다. 고유 조회 수 내림차순. */
  List<PopularRow> top(Instant since, int minUniqueViews, int limit);

  /** since 이후 제외 목록을 뺀 전체 고유 조회 수. */
  long totalUniqueViews(Instant since);

  int purgeBefore(Instant before);

  Optional<PopularResult> findCached(String key);

  void saveCached(String key, PopularResult result);

  record PopularRow(
      String characterName, String serverName, String className, BigDecimal itemLevel) {}

  record PopularResult(
      String period,
      Instant aggregatedAt,
      String sampleStatus,
      int minUniqueViews,
      List<PopularItem> items) {}

  record PopularItem(
      int rank, String characterName, String serverName, String className, BigDecimal itemLevel) {}
}
