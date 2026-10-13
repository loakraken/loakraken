package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterPopularPolicy;
import com.loakraken.character.application.CharacterPopularPort;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 중복 제거 키(popular:v1:seen:{KST 날짜}, 26시간)와 결과 캐시(popular:v1:result:*)는 Valkey, 시간별 고유 조회 수는
 * Postgres.
 */
@Repository
class CharacterPopularStore implements CharacterPopularPort {
  static final String SEEN_PREFIX = "popular:v1:seen:";
  static final String RESULT_PREFIX = "popular:v1:result:";
  static final String GUILD_RESULT_PREFIX = "popular:v1:guilds:";
  private static final Duration SEEN_TTL = Duration.ofHours(26);
  private final JdbcClient jdbc;
  private final StringRedisTemplate redis;
  private final JsonMapper mapper;
  private final CharacterPopularPolicy policy;

  CharacterPopularStore(
      JdbcClient jdbc,
      StringRedisTemplate redis,
      JsonMapper mapper,
      CharacterPopularPolicy policy) {
    this.jdbc = jdbc;
    this.redis = redis;
    this.mapper = mapper;
    this.policy = policy;
  }

  @Override
  public boolean markSeen(LocalDate day, String clientKey) {
    String key = SEEN_PREFIX + day;
    var added = redis.opsForSet().add(key, clientKey);
    // 키가 남아 있어도 만료를 다시 걸어 TTL 없는 키가 생기지 않게 한다.
    redis.expire(key, SEEN_TTL);
    return added != null && added > 0;
  }

  @Override
  public void increment(String characterName, String serverName, Instant hourBucket) {
    jdbc.sql(
            """
        INSERT INTO character_popular_hourly (character_name, server_name, hour_bucket, unique_views)
        VALUES (:name, :server, :hour, 1)
        ON CONFLICT (character_name, server_name, hour_bucket)
        DO UPDATE SET unique_views = character_popular_hourly.unique_views + 1
        """)
        .param("name", characterName)
        .param("server", serverName)
        .param("hour", utc(hourBucket))
        .update();
  }

  @Override
  public List<PopularRow> top(Instant since, int minUniqueViews, int limit) {
    return jdbc.sql(
            """
        SELECT t.character_name, t.server_name, c.class_name, c.item_level
        FROM (
          SELECT h.character_name, h.server_name, SUM(h.unique_views) AS views, MAX(h.hour_bucket) AS last_hour
          FROM character_popular_hourly h
          WHERE h.hour_bucket >= :since
            AND NOT EXISTS (
              SELECT 1 FROM public_exclusion e
              WHERE e.character_name = h.character_name
                AND (e.server_name IS NULL OR e.server_name = h.server_name))
          GROUP BY h.character_name, h.server_name
          HAVING SUM(h.unique_views) >= :min
        ) t
        LEFT JOIN characters c ON c.server_name = t.server_name AND c.character_name = t.character_name
        ORDER BY t.views DESC, t.last_hour DESC, t.character_name, t.server_name
        LIMIT :limit
        """)
        .param("since", utc(since))
        .param("min", minUniqueViews)
        .param("limit", limit)
        .query(
            (row, index) ->
                new PopularRow(
                    row.getString("character_name"),
                    row.getString("server_name"),
                    row.getString("class_name"),
                    row.getBigDecimal("item_level")))
        .list();
  }

  @Override
  public List<PopularGuildRow> topGuilds(
      Instant since, String serverName, int minUniqueViews, int limit) {
    // 인기 캐릭터와 같은 시간 버킷 합. 길드는 지금 캐릭터 행의 길드 이름이고, 처리정지 캐릭터 조회는 합에 넣지 않는다(길드는 남김, B5).
    return jdbc.sql(
            """
        SELECT c.server_name, c.guild_name
        FROM character_popular_hourly h
        JOIN characters c ON c.server_name = h.server_name AND c.character_name = h.character_name
        WHERE h.hour_bucket >= :since
          AND c.guild_name IS NOT NULL
          AND (CAST(:server AS VARCHAR) IS NULL OR c.server_name = CAST(:server AS VARCHAR))
          AND NOT EXISTS (
            SELECT 1 FROM public_exclusion e
            WHERE e.character_name = h.character_name
              AND (e.server_name IS NULL OR e.server_name = h.server_name))
        GROUP BY c.server_name, c.guild_name
        HAVING SUM(h.unique_views) >= :min
        ORDER BY SUM(h.unique_views) DESC, c.guild_name, c.server_name
        LIMIT :limit
        """)
        .param("since", utc(since))
        .param("server", serverName)
        .param("min", minUniqueViews)
        .param("limit", limit)
        .query(
            (row, index) ->
                new PopularGuildRow(row.getString("server_name"), row.getString("guild_name")))
        .list();
  }

  @Override
  public Optional<PopularGuildResult> findCachedGuilds(String key) {
    try {
      var json = redis.opsForValue().get(GUILD_RESULT_PREFIX + key);
      return json == null
          ? Optional.empty()
          : Optional.of(mapper.readValue(json, PopularGuildResult.class));
    } catch (DataAccessException | JacksonException | IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  @Override
  public void saveCachedGuilds(String key, PopularGuildResult result) {
    try {
      redis
          .opsForValue()
          .set(GUILD_RESULT_PREFIX + key, mapper.writeValueAsString(result), policy.cacheTtl());
    } catch (JacksonException exception) {
      // 캐시를 쓰지 못해도 응답은 그대로 준다.
    }
  }

  @Override
  public long totalUniqueViews(Instant since) {
    return jdbc.sql(
            """
        SELECT COALESCE(SUM(h.unique_views), 0) FROM character_popular_hourly h
        WHERE h.hour_bucket >= :since
          AND NOT EXISTS (
            SELECT 1 FROM public_exclusion e
            WHERE e.character_name = h.character_name
              AND (e.server_name IS NULL OR e.server_name = h.server_name))
        """)
        .param("since", utc(since))
        .query(Long.class)
        .single();
  }

  @Override
  public int purgeBefore(Instant before) {
    return jdbc.sql("DELETE FROM character_popular_hourly WHERE hour_bucket < :before")
        .param("before", utc(before))
        .update();
  }

  @Override
  public Optional<PopularResult> findCached(String key) {
    try {
      var json = redis.opsForValue().get(RESULT_PREFIX + key);
      return json == null
          ? Optional.empty()
          : Optional.of(mapper.readValue(json, PopularResult.class));
    } catch (DataAccessException | JacksonException | IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  @Override
  public void saveCached(String key, PopularResult result) {
    try {
      redis
          .opsForValue()
          .set(RESULT_PREFIX + key, mapper.writeValueAsString(result), policy.cacheTtl());
    } catch (JacksonException exception) {
      // 캐시를 쓰지 못해도 응답은 그대로 준다.
    }
  }

  private static OffsetDateTime utc(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }
}
