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

  /**
   * since 이후(포함) 길드원 고유 조회 합이 minUniqueViews 이상인 길드(M21). 길드는 집계 시점 characters.guild_name이고, 처리정지
   * 캐릭터의 조회는 합에서 빼지만 길드는 남긴다. 합 내림차순, 같으면 길드 이름·서버 순. serverName이 null이면 모든 서버.
   */
  List<PopularGuildRow> topGuilds(Instant since, String serverName, int minUniqueViews, int limit);

  Optional<PopularGuildResult> findCachedGuilds(String key);

  void saveCachedGuilds(String key, PopularGuildResult result);

  record PopularGuildRow(String serverName, String guildName) {}

  record PopularGuildResult(
      String period, Instant aggregatedAt, String sampleStatus, List<PopularGuildItem> items) {}

  record PopularGuildItem(int rank, String serverName, String guildName) {}

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
