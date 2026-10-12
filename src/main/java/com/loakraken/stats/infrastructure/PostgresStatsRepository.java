package com.loakraken.stats.infrastructure;

import com.loakraken.stats.application.StatsPort;
import com.loakraken.stats.application.StatsPort.GuildSort;
import com.loakraken.stats.application.StatsPort.RosterAggregate;
import com.loakraken.stats.application.StatsPort.RosterBasis;
import com.loakraken.stats.domain.StatsConditions;
import com.loakraken.stats.domain.StatsRules.Metric;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * characters를 읽기 전용으로 집계한다. 모집단 = 프로필(전투력)을 확인했고 since 이후 갱신한 캐릭터. 모든 필터 값은 바인딩 파라미터이고, 열 이름은
 * Metric enum에서만 온다.
 */
@Repository
@Transactional(readOnly = true, timeout = 5)
class PostgresStatsRepository implements StatsPort {
  private final JdbcClient jdbc;

  PostgresStatsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Summary summary(StatsConditions conditions, Instant since) {
    var population = population(conditions, since);
    return population
        .statement(
            """
        SELECT COUNT(*) AS n, AVG(item_level) AS il_mean,
          percentile_cont(0.5) WITHIN GROUP (ORDER BY item_level) AS il_median,
          percentile_cont(0.5) WITHIN GROUP (ORDER BY combat_power) AS cp_median,
          percentile_cont(0.9) WITHIN GROUP (ORDER BY combat_power) AS cp_p90,
          COUNT(*) FILTER (WHERE position = 'DEALER') AS dealers,
          COUNT(*) FILTER (WHERE position = 'SUPPORT') AS supports,
          AVG(gem_mean_level) AS gem_mean, COUNT(gem_mean_level) AS gem_n
        FROM pop
        """,
            jdbc)
        .query(
            (row, index) ->
                new Summary(
                    row.getLong("n"),
                    round(row.getBigDecimal("il_mean")),
                    round(row.getBigDecimal("il_median")),
                    round(row.getBigDecimal("cp_median")),
                    round(row.getBigDecimal("cp_p90")),
                    row.getLong("dealers"),
                    row.getLong("supports"),
                    round(row.getBigDecimal("gem_mean")),
                    row.getLong("gem_n")))
        .single();
  }

  @Override
  public List<ClassRow> classes(StatsConditions conditions, Instant since) {
    return population(conditions, since)
        .statement(
            """
        SELECT class_name, COUNT(*) AS n,
          COUNT(*) FILTER (WHERE position = 'DEALER') AS dealers,
          COUNT(*) FILTER (WHERE position = 'SUPPORT') AS supports,
          AVG(item_level) AS il_mean, AVG(gem_mean_level) AS gem_mean,
          percentile_cont(ARRAY[0.1, 0.25, 0.5, 0.75, 0.9]) WITHIN GROUP (ORDER BY combat_power) AS cp
        FROM pop GROUP BY class_name ORDER BY n DESC, class_name
        """,
            jdbc)
        .query(
            (row, index) ->
                new ClassRow(
                    row.getString("class_name"),
                    row.getLong("n"),
                    row.getLong("dealers"),
                    row.getLong("supports"),
                    round(row.getBigDecimal("il_mean")),
                    round(row.getBigDecimal("gem_mean")),
                    percentiles(row.getArray("cp"))))
        .list();
  }

  @Override
  public List<ServerRow> servers(StatsConditions conditions, Instant since) {
    // 모집단이 0명인 서버도 알고 있는 캐릭터·원정대 수는 보여 준다.
    var rows =
        population(conditions, since)
            .statement(
                """
            , known AS (
              SELECT server_name, COUNT(*) AS known, COUNT(DISTINCT roster_key) AS rosters
              FROM characters GROUP BY server_name),
            stat AS (
              SELECT server_name, COUNT(*) AS n,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY item_level) AS il_median,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY combat_power) AS cp_median
              FROM pop GROUP BY server_name)
            SELECT k.server_name, COALESCE(s.n, 0) AS n, s.il_median, s.cp_median, k.known, k.rosters
            FROM known k LEFT JOIN stat s ON s.server_name = k.server_name
            ORDER BY n DESC, k.known DESC, k.server_name
            """,
                jdbc)
            .query(
                (row, index) ->
                    new ServerRow(
                        row.getString("server_name"),
                        row.getLong("n"),
                        round(row.getBigDecimal("il_median")),
                        round(row.getBigDecimal("cp_median")),
                        row.getLong("known"),
                        row.getLong("rosters")))
            .list();
    if (conditions.server() == null) return rows;
    return rows.stream().filter(row -> row.serverName().equals(conditions.server())).toList();
  }

  @Override
  public Range range(StatsConditions conditions, Instant since, Metric metric) {
    String column = metric.column();
    return population(conditions, since)
        .statement(
            "SELECT COUNT(*) AS n, MIN(" + column + ") AS lo, MAX(" + column + ") AS hi FROM pop",
            jdbc)
        .query(
            (row, index) ->
                new Range(row.getLong("n"), row.getBigDecimal("lo"), row.getBigDecimal("hi")))
        .single();
  }

  @Override
  public List<Bin> histogram(
      StatsConditions conditions, Instant since, Metric metric, BigDecimal width) {
    String column = metric.column();
    return population(conditions, since)
        .statement(
            "SELECT FLOOR("
                + column
                + " / :width) * :width AS lower_bound, COUNT(*) AS n FROM pop GROUP BY 1 ORDER BY 1",
            jdbc)
        .param("width", width)
        .query(
            (row, index) ->
                new Bin(row.getBigDecimal("lower_bound").stripTrailingZeros(), row.getLong("n")))
        .list();
  }

  @Override
  public Standing standing(
      StatsConditions conditions, Instant since, Metric metric, BigDecimal value) {
    String column = metric.column();
    return population(conditions, since, true)
        .statement(
            "SELECT COUNT(*) FILTER (WHERE "
                + column
                + " > :value) AS greater, COUNT(*) AS n FROM pop",
            jdbc)
        .param("value", value)
        .query((row, index) -> new Standing(row.getLong("greater"), row.getLong("n")))
        .single();
  }

  /** 수집품 랭킹 공통 CTE(#295). */
  private static final String COLLECTIBLE_RANKED =
      """
      WITH totals AS (
        SELECT roster_key, SUM(point) AS point, SUM(max_point) AS max_point, MAX(observed_at) AS observed_at
        FROM roster_collectible GROUP BY roster_key
        HAVING MAX(observed_at) >= :since AND SUM(point) > 0 AND SUM(max_point) > 0),
      members AS (
        SELECT c.roster_key, c.character_name, c.server_name, c.class_name, c.item_level,
          ROW_NUMBER() OVER (PARTITION BY c.roster_key ORDER BY c.item_level DESC NULLS LAST, c.character_name) AS rn,
          COUNT(*) OVER (PARTITION BY c.roster_key) AS member_count
        FROM characters c JOIN totals t ON t.roster_key = c.roster_key
        WHERE NOT EXISTS (
            SELECT 1 FROM public_exclusion e
            WHERE e.character_name = c.character_name AND (e.server_name IS NULL OR e.server_name = c.server_name))
          AND (CAST(:server AS VARCHAR) IS NULL OR c.server_name = CAST(:server AS VARCHAR))),
      ranked AS (
        SELECT t.roster_key, t.point, t.max_point, t.observed_at, m.character_name, m.server_name, m.class_name,
          m.item_level, m.member_count,
          RANK() OVER (ORDER BY ROUND(t.point * 100.0 / t.max_point, 2) DESC, t.point DESC) AS rank
        FROM totals t JOIN members m ON m.roster_key = t.roster_key AND (:characterUnit OR m.rn = 1))
      """;

  @Override
  public CollectiblePage collectibleRanking(
      String server, Instant since, boolean characterUnit, int offset, int limit) {
    // 전체 수는 행과 같은 조회에서 COUNT(*) OVER()로 받는다. 페이지가 범위를 넘어 행이 없을 때만 따로 센다.
    var totals = new long[] {0};
    var rows =
        jdbc.sql(
                COLLECTIBLE_RANKED
                    + "SELECT *, COUNT(*) OVER () AS total FROM ranked ORDER BY rank, character_name LIMIT :limit OFFSET :offset")
            .param("since", since.atOffset(ZoneOffset.UTC))
            .param("server", server)
            .param("characterUnit", characterUnit)
            .param("limit", limit)
            .param("offset", offset)
            .query(
                (row, index) -> {
                  totals[0] = row.getLong("total");
                  return new CollectibleRow(
                      row.getLong("rank"),
                      row.getObject("roster_key", java.util.UUID.class),
                      row.getString("character_name"),
                      row.getString("server_name"),
                      row.getString("class_name"),
                      row.getBigDecimal("item_level"),
                      row.getLong("member_count"),
                      row.getLong("point"),
                      row.getLong("max_point"),
                      row.getTimestamp("observed_at").toInstant());
                })
            .list();
    long total = totals[0];
    if (rows.isEmpty() && offset > 0)
      total =
          jdbc.sql(COLLECTIBLE_RANKED + "SELECT COUNT(*) FROM ranked")
              .param("since", since.atOffset(ZoneOffset.UTC))
              .param("server", server)
              .param("characterUnit", characterUnit)
              .query(Long.class)
              .single();
    return new CollectiblePage(total, rows);
  }

  /**
   * 원정대 랭킹 공통 CTE(#297). %VALUE%는 RosterBasis에서만 오는 고정 식이고 %KEYS%는 빈 문자열 또는 roster_key 바인딩 조건이다. 관측
   * = 검색·프로필 갱신·원정대 확인 중 가장 늦은 시각이 since 뒤.
   */
  private static final String ROSTER_VALUED =
      """
      WITH members AS (
        SELECT c.roster_key, c.character_name, c.server_name, c.class_name, c.item_level, c.combat_power,
          c.expedition_level, (c.combat_power IS NOT NULL
            AND GREATEST(c.profile_refreshed_at, c.last_synced_at) >= :since) AS cp_observed
        FROM characters c
        WHERE c.roster_key IS NOT NULL
          AND GREATEST(c.last_synced_at, c.profile_refreshed_at, c.roster_scanned_at) >= :since
          AND (CAST(:server AS VARCHAR) IS NULL OR c.server_name = CAST(:server AS VARCHAR))
          AND NOT EXISTS (
            SELECT 1 FROM public_exclusion e
            WHERE e.character_name = c.character_name AND (e.server_name IS NULL OR e.server_name = c.server_name))
          %KEYS%),
      valued AS (
        SELECT m.*, %VALUE% AS value,
          ROW_NUMBER() OVER (PARTITION BY m.roster_key
            ORDER BY %VALUE% DESC NULLS LAST, m.item_level DESC NULLS LAST, m.character_name) AS vrn,
          ROW_NUMBER() OVER (PARTITION BY m.roster_key
            ORDER BY m.item_level DESC NULLS LAST, m.character_name) AS irn
        FROM members m)
      """;

  private static final String ROSTER_RANKED =
      """
      , rosters AS (
        SELECT roster_key, COUNT(*) AS member_count,
          COUNT(value) FILTER (WHERE vrn <= 6) AS used_top6, COUNT(value) AS used_all,
          AVG(value) FILTER (WHERE vrn <= 6) AS top6_avg, SUM(value) FILTER (WHERE vrn <= 6) AS top6_sum,
          AVG(value) AS all_avg, MAX(expedition_level) AS roster_level, COUNT(expedition_level) AS used_level,
          BOOL_OR(item_level >= 1640 AND NOT cp_observed) AS cp_missing
        FROM valued GROUP BY roster_key),
      scored AS (
        SELECT r.*, %SCORE% AS score, %USED% AS used FROM rosters r WHERE NOT (:cpBasis AND r.cp_missing)),
      ranked AS (
        SELECT s.roster_key, s.member_count, s.used, s.score, v.character_name, v.server_name, v.class_name,
          v.item_level, v.combat_power, RANK() OVER (ORDER BY ROUND(s.score, 2) DESC) AS rank
        FROM scored s JOIN valued v ON v.roster_key = s.roster_key AND v.irn = 1
        WHERE s.score IS NOT NULL)
      """;

  private static String rosterValue(RosterBasis basis) {
    return switch (basis) {
      case ITEM_LEVEL, ROSTER_LEVEL -> "m.item_level";
      case COMBAT_POWER -> "CASE WHEN m.cp_observed THEN m.combat_power END";
    };
  }

  private static String rosterSql(RosterBasis basis, RosterAggregate aggregate) {
    String score;
    String used;
    if (basis == RosterBasis.ROSTER_LEVEL) {
      score = "r.roster_level";
      used = "r.used_level";
    } else {
      score =
          switch (aggregate) {
            case TOP6_AVG -> "r.top6_avg";
            case TOP6_SUM -> "r.top6_sum";
            case ALL_AVG -> "r.all_avg";
          };
      used = aggregate == RosterAggregate.ALL_AVG ? "r.used_all" : "r.used_top6";
    }
    return ROSTER_VALUED.replace("%KEYS%", "").replace("%VALUE%", rosterValue(basis))
        + ROSTER_RANKED.replace("%SCORE%", score).replace("%USED%", used);
  }

  @Override
  public RosterPage rosterRanking(
      String server,
      Instant since,
      RosterBasis basis,
      RosterAggregate aggregate,
      int offset,
      int limit) {
    String base = rosterSql(basis, aggregate);
    boolean cpBasis = basis == RosterBasis.COMBAT_POWER;
    // 전체 수·제외 수는 행과 같은 조회에서 받는다. 행이 없을 때만 따로 센다.
    var counts = new long[] {0, 0};
    var rows =
        jdbc.sql(
                base
                    + """
                SELECT ranked.*, COUNT(*) OVER () AS total,
                  (SELECT COUNT(*) FROM rosters WHERE :cpBasis AND cp_missing) AS excluded
                FROM ranked ORDER BY rank, character_name LIMIT :limit OFFSET :offset
                """)
            .param("since", since.atOffset(ZoneOffset.UTC))
            .param("server", server)
            .param("cpBasis", cpBasis)
            .param("limit", limit)
            .param("offset", offset)
            .query(
                (row, index) -> {
                  counts[0] = row.getLong("total");
                  counts[1] = row.getLong("excluded");
                  return new RosterRow(
                      row.getLong("rank"),
                      row.getObject("roster_key", java.util.UUID.class),
                      row.getString("character_name"),
                      row.getString("server_name"),
                      row.getString("class_name"),
                      row.getBigDecimal("item_level"),
                      row.getBigDecimal("combat_power"),
                      row.getLong("member_count"),
                      row.getLong("used"),
                      row.getBigDecimal("score"));
                })
            .list();
    if (rows.isEmpty())
      jdbc.sql(
              base
                  + "SELECT (SELECT COUNT(*) FROM ranked), (SELECT COUNT(*) FROM rosters WHERE :cpBasis AND cp_missing)")
          .param("since", since.atOffset(ZoneOffset.UTC))
          .param("server", server)
          .param("cpBasis", cpBasis)
          .query(
              (ResultSet row) -> {
                counts[0] = row.getLong(1);
                counts[1] = row.getLong(2);
              });
    return new RosterPage(counts[0], counts[1], rows);
  }

  @Override
  public java.util.Map<java.util.UUID, List<RosterMember>> rosterComposition(
      java.util.Collection<java.util.UUID> rosterKeys,
      String server,
      Instant since,
      RosterBasis basis) {
    var result = new java.util.LinkedHashMap<java.util.UUID, List<RosterMember>>();
    if (rosterKeys.isEmpty()) return result;
    jdbc.sql(
            ROSTER_VALUED
                    .replace("%KEYS%", "AND c.roster_key IN (:keys)")
                    .replace("%VALUE%", rosterValue(basis))
                + "SELECT roster_key, character_name, class_name, value FROM valued"
                + " WHERE vrn <= 6 AND value IS NOT NULL ORDER BY roster_key, vrn")
        .param("since", since.atOffset(ZoneOffset.UTC))
        .param("server", server)
        .param("keys", rosterKeys)
        .query(
            (ResultSet row) -> {
              result
                  .computeIfAbsent(row.getObject(1, java.util.UUID.class), key -> new ArrayList<>())
                  .add(new RosterMember(row.getString(2), row.getString(3), row.getBigDecimal(4)));
            });
    return result;
  }

  /**
   * 길드 랭킹 공통 CTE(#301). 길드 이름은 관측 이력 기록(profile_refreshed_at도 함께 갱신)에서만 쓰므로, 관측 =
   * profile_refreshed_at이 since 뒤(검색 스냅숏만 갱신된 행은 길드 이름이 오래됐을 수 있어 뺀다). %ORDER%는 GuildSort에서만 오는 고정
   * 식이다.
   */
  private static final String GUILD_RANKED =
      """
      WITH guilds AS (
        SELECT c.server_name, c.guild_name, COUNT(*) AS member_count, AVG(c.item_level) AS il_avg,
          SUM(c.item_level) AS il_sum,
          AVG(c.combat_power) FILTER (WHERE c.combat_power IS NOT NULL) AS cp_avg,
          COUNT(c.combat_power) AS cp_count
        FROM characters c
        WHERE c.guild_name IS NOT NULL AND c.item_level IS NOT NULL
          AND c.profile_refreshed_at >= :since
          AND (CAST(:server AS VARCHAR) IS NULL OR c.server_name = CAST(:server AS VARCHAR))
          AND NOT EXISTS (
            SELECT 1 FROM public_exclusion e
            WHERE e.character_name = c.character_name AND (e.server_name IS NULL OR e.server_name = c.server_name))
        GROUP BY c.server_name, c.guild_name),
      ranked AS (
        SELECT g.*, RANK() OVER (ORDER BY ROUND(%ORDER%, 2) DESC) AS rank
        FROM guilds g WHERE g.member_count >= :minMembers)
      """;

  @Override
  public GuildPage guildRanking(
      String server, Instant since, GuildSort sort, int minMembers, int offset, int limit) {
    String base = GUILD_RANKED.replace("%ORDER%", sort == GuildSort.SUM ? "g.il_sum" : "g.il_avg");
    var counts = new long[] {0, 0};
    var rows =
        jdbc.sql(
                base
                    + """
                SELECT ranked.*, COUNT(*) OVER () AS total,
                  (SELECT COUNT(*) FROM guilds WHERE member_count < :minMembers) AS excluded
                FROM ranked ORDER BY rank, server_name, guild_name LIMIT :limit OFFSET :offset
                """)
            .param("since", since.atOffset(ZoneOffset.UTC))
            .param("server", server)
            .param("minMembers", minMembers)
            .param("limit", limit)
            .param("offset", offset)
            .query(
                (row, index) -> {
                  counts[0] = row.getLong("total");
                  counts[1] = row.getLong("excluded");
                  return new GuildRow(
                      row.getLong("rank"),
                      row.getString("server_name"),
                      row.getString("guild_name"),
                      row.getLong("member_count"),
                      row.getBigDecimal("il_avg"),
                      row.getBigDecimal("il_sum"),
                      row.getBigDecimal("cp_avg"),
                      row.getLong("cp_count"));
                })
            .list();
    if (rows.isEmpty())
      jdbc.sql(
              base
                  + "SELECT (SELECT COUNT(*) FROM ranked), (SELECT COUNT(*) FROM guilds WHERE member_count < :minMembers)")
          .param("since", since.atOffset(ZoneOffset.UTC))
          .param("server", server)
          .param("minMembers", minMembers)
          .query(
              (ResultSet row) -> {
                counts[0] = row.getLong(1);
                counts[1] = row.getLong(2);
              });
    return new GuildPage(counts[0], counts[1], rows);
  }

  /** 보유율 분모 원정대(#305): since 뒤 항목 관측 + 공개 캐릭터가 있음(server가 있으면 그 서버). */
  private static final String OWNERSHIP_ROSTERS =
      """
      WITH rosters AS (
        SELECT r.roster_key, r.item_ids, r.points FROM roster_collectible_item r
        WHERE r.observed_at >= :since
          AND EXISTS (
            SELECT 1 FROM characters c
            WHERE c.roster_key = r.roster_key
              AND (CAST(:server AS VARCHAR) IS NULL OR c.server_name = CAST(:server AS VARCHAR))
              AND NOT EXISTS (
                SELECT 1 FROM public_exclusion e
                WHERE e.character_name = c.character_name
                  AND (e.server_name IS NULL OR e.server_name = c.server_name))))
      """;

  @Override
  public List<String> collectibleServers() {
    return jdbc.sql(
            """
        SELECT DISTINCT c.server_name FROM roster_collectible_item r
        JOIN characters c ON c.roster_key = r.roster_key ORDER BY c.server_name
        """)
        .query(String.class)
        .list();
  }

  @Override
  public CollectibleOwnership collectibleOwnership(String server, Instant since, String type) {
    long population =
        jdbc.sql(OWNERSHIP_ROSTERS + "SELECT COUNT(*) FROM rosters")
            .param("since", since.atOffset(ZoneOffset.UTC))
            .param("server", server)
            .query(Long.class)
            .single();
    var items =
        jdbc.sql(
                OWNERSHIP_ROSTERS
                    + """
                , held AS (
                  SELECT u.item_id, u.point FROM rosters r
                  CROSS JOIN LATERAL unnest(r.item_ids, r.points) AS u(item_id, point))
                SELECT i.type, i.name, i.type_order, i.sort_order, i.max_point,
                  COUNT(h.item_id) FILTER (WHERE h.point > 0) AS owned,
                  COUNT(h.item_id) FILTER (WHERE h.point >= i.max_point) AS completed
                FROM collectible_item i LEFT JOIN held h ON h.item_id = i.item_id
                WHERE (CAST(:type AS VARCHAR) IS NULL OR i.type = CAST(:type AS VARCHAR))
                  AND i.last_seen_at >= :since
                GROUP BY i.item_id, i.type, i.name, i.type_order, i.sort_order, i.max_point
                ORDER BY i.type_order, i.type, i.sort_order, i.name
                """)
            .param("since", since.atOffset(ZoneOffset.UTC))
            .param("server", server)
            .param("type", type)
            .query(
                (row, index) ->
                    new CollectibleOwnershipRow(
                        row.getString("type"),
                        row.getString("name"),
                        row.getInt("sort_order"),
                        row.getInt("max_point"),
                        row.getLong("owned"),
                        row.getLong("completed")))
            .list();
    return new CollectibleOwnership(population, items);
  }

  @Override
  public CollectibleStanding collectibleStanding(
      String characterName, String server, Instant since, boolean characterUnit) {
    var owner =
        jdbc.sql(
                """
            SELECT c.character_name, c.server_name, c.roster_key,
              (SELECT MAX(r.observed_at) FROM roster_collectible r WHERE r.roster_key = c.roster_key) AS observed_at,
              (SELECT SUM(r.point) FROM roster_collectible r WHERE r.roster_key = c.roster_key) AS point
            FROM characters c
            WHERE c.character_name = :name
              AND (CAST(:server AS VARCHAR) IS NULL OR c.server_name = CAST(:server AS VARCHAR))
            ORDER BY c.server_name LIMIT 1
            """)
            .param("name", characterName)
            .param("server", server)
            .query(
                (row, index) ->
                    new Object[] {
                      row.getString(1),
                      row.getString(2),
                      row.getObject(3, java.util.UUID.class),
                      row.getObject(4, OffsetDateTime.class),
                      row.getObject(5, Long.class)
                    })
            .optional();
    long total =
        jdbc.sql(COLLECTIBLE_RANKED + "SELECT COUNT(*) FROM ranked")
            .param("since", since.atOffset(ZoneOffset.UTC))
            .param("server", server)
            .param("characterUnit", characterUnit)
            .query(Long.class)
            .single();
    // 처리정지 목록 캐릭터는 관측이 없는 이름과 같은 응답이다(#363, EXCLUDED는 더 이상 쓰지 않음).
    boolean excluded =
        jdbc.sql(
                """
            SELECT EXISTS (SELECT 1 FROM public_exclusion e WHERE e.character_name = :name
              AND (e.server_name IS NULL OR CAST(:server AS VARCHAR) IS NULL
                   OR e.server_name = CAST(:server AS VARCHAR)))
            """)
            .param("name", characterName)
            .param("server", server)
            .query(Boolean.class)
            .single();
    if (excluded)
      return new CollectibleStanding("NOT_OBSERVED", characterName, null, null, total, null, null);
    if (owner.isEmpty())
      return new CollectibleStanding("NOT_OBSERVED", characterName, null, null, total, null, null);
    var found = owner.get();
    String name = (String) found[0];
    String serverName = (String) found[1];
    var rosterKey = (java.util.UUID) found[2];
    var observedAt = (OffsetDateTime) found[3];
    if (rosterKey == null || observedAt == null)
      return new CollectibleStanding("NOT_OBSERVED", name, serverName, null, total, null, null);
    if (observedAt.toInstant().isBefore(since))
      return new CollectibleStanding("INACTIVE", name, serverName, null, total, null, null);
    if (found[4] == null || (Long) found[4] <= 0)
      return new CollectibleStanding("NO_COLLECTIBLES", name, serverName, null, total, null, null);
    return jdbc.sql(
            COLLECTIBLE_RANKED
                + "SELECT rank, point, max_point FROM ranked WHERE roster_key = :roster"
                + " AND (NOT :characterUnit OR character_name = :name) LIMIT 1")
        .param("since", since.atOffset(ZoneOffset.UTC))
        .param("server", server)
        .param("characterUnit", characterUnit)
        .param("roster", rosterKey)
        .param("name", name)
        .query(
            (row, index) ->
                new CollectibleStanding(
                    null, name, serverName, row.getLong(1), total, row.getLong(2), row.getLong(3)))
        .optional()
        // 원정대의 공개 캐릭터가 모두 서버 필터 밖이면 순위에 없다.
        .orElse(new CollectibleStanding("NOT_OBSERVED", name, serverName, null, total, null, null));
  }

  /** 칭호 장착 확인 공통 CTE(#311): 프로필을 관측한 공개 캐릭터(원정대 키가 있는). */
  private static final String TITLE_MEMBERS =
      """
      WITH members AS (
        SELECT c.character_id, c.roster_key FROM characters c
        WHERE c.roster_key IS NOT NULL AND c.profile_refreshed_at >= :since
          AND (CAST(:server AS VARCHAR) IS NULL OR c.server_name = CAST(:server AS VARCHAR))
          AND NOT EXISTS (
            SELECT 1 FROM public_exclusion e
            WHERE e.character_name = c.character_name AND (e.server_name IS NULL OR e.server_name = c.server_name)))
      """;

  @Override
  public TitleEquip titleEquip(String server, Instant since, String title) {
    long population =
        jdbc.sql(TITLE_MEMBERS + "SELECT COUNT(DISTINCT roster_key) FROM members")
            .param("since", since.atOffset(ZoneOffset.UTC))
            .param("server", server)
            .query(Long.class)
            .single();
    var titles =
        jdbc.sql(
                TITLE_MEMBERS
                    + """
                SELECT t.title, COUNT(DISTINCT m.roster_key) AS rosters
                FROM character_title_observation t JOIN members m ON m.character_id = t.character_id
                WHERE t.last_observed_at >= :since
                  AND (CAST(:title AS VARCHAR) IS NULL OR t.title = CAST(:title AS VARCHAR))
                GROUP BY t.title ORDER BY rosters DESC, t.title COLLATE "C"
                """)
            .param("since", since.atOffset(ZoneOffset.UTC))
            .param("server", server)
            .param("title", title)
            .query((row, index) -> new TitleEquipRow(row.getString(1), row.getLong(2)))
            .list();
    return new TitleEquip(population, titles);
  }

  @Override
  public java.util.Map<java.util.UUID, List<CollectibleType>> collectibleTypes(
      java.util.Collection<java.util.UUID> rosterKeys) {
    var result = new java.util.LinkedHashMap<java.util.UUID, List<CollectibleType>>();
    if (rosterKeys.isEmpty()) return result;
    jdbc.sql(
            "SELECT roster_key, type, point, max_point FROM roster_collectible WHERE roster_key IN (:keys) ORDER BY roster_key, sort_order")
        .param("keys", rosterKeys)
        .query(
            (java.sql.ResultSet row) -> {
              result
                  .computeIfAbsent(row.getObject(1, java.util.UUID.class), key -> new ArrayList<>())
                  .add(new CollectibleType(row.getString(2), row.getInt(3), row.getInt(4)));
            });
    return result;
  }

  @Override
  public RankingPage ranking(
      StatsConditions conditions, Instant since, Metric metric, int offset, int limit) {
    String column = metric.column();
    String tie = metric == Metric.COMBAT_POWER ? "item_level" : "combat_power";
    var rows = new ArrayList<RankingRow>();
    long[] total = {0};
    population(conditions, since, true)
        .statement(
            "SELECT character_name, server_name, class_name, position, item_level, combat_power,"
                + " RANK() OVER (ORDER BY "
                + column
                + " DESC) AS rnk, COUNT(*) OVER () AS total FROM pop ORDER BY "
                + column
                + " DESC, "
                + tie
                + " DESC, character_name, server_name LIMIT :limit OFFSET :offset",
            jdbc)
        .param("limit", limit)
        .param("offset", offset)
        .query(
            (ResultSet row) -> {
              total[0] = row.getLong("total");
              rows.add(
                  new RankingRow(
                      row.getLong("rnk"),
                      row.getString("character_name"),
                      row.getString("server_name"),
                      row.getString("class_name"),
                      row.getString("position"),
                      row.getBigDecimal("item_level"),
                      row.getBigDecimal("combat_power")));
            });
    // 요청 페이지가 범위를 넘으면 행이 없어 total을 따로 센다.
    if (rows.isEmpty())
      total[0] =
          population(conditions, since, true)
              .statement("SELECT COUNT(*) FROM pop", jdbc)
              .query(Long.class)
              .single();
    return new RankingPage(total[0], rows);
  }

  @Override
  public EfficiencyPage efficiencyRanking(
      StatsConditions conditions, Instant since, EfficiencyKind kind, int offset, int limit) {
    String column = kind.column();
    String versionColumn = kind.versionColumn();
    // 지금 규칙 버전(공통 상수) 값만 센다. 모델이 바뀌면 다음 관측 때 다시 저장된 캐릭터부터 들어간다.
    var version = kind.ruleVersion();
    var rows = new ArrayList<EfficiencyRow>();
    long[] total = {0};
    String filter = " WHERE " + column + " IS NOT NULL AND " + versionColumn + " = :version";
    population(conditions, since, true)
        .statement(
            "SELECT character_name, server_name, class_name, position, item_level, combat_power, "
                + column
                + " AS value, efficiency_observed_at, RANK() OVER (ORDER BY "
                + column
                + " DESC) AS rnk, COUNT(*) OVER () AS total FROM pop"
                + filter
                + " ORDER BY "
                + column
                + " DESC, combat_power DESC, character_name, server_name LIMIT :limit OFFSET :offset",
            jdbc)
        .param("version", version)
        .param("limit", limit)
        .param("offset", offset)
        .query(
            (ResultSet row) -> {
              total[0] = row.getLong("total");
              var observed =
                  row.getObject("efficiency_observed_at", java.time.OffsetDateTime.class);
              rows.add(
                  new EfficiencyRow(
                      row.getLong("rnk"),
                      row.getString("character_name"),
                      row.getString("server_name"),
                      row.getString("class_name"),
                      row.getString("position"),
                      row.getBigDecimal("item_level"),
                      row.getBigDecimal("combat_power"),
                      row.getBigDecimal("value"),
                      observed == null ? null : observed.toInstant()));
            });
    // 요청 페이지가 범위를 넘으면 행이 없어 total을 따로 센다.
    if (rows.isEmpty())
      total[0] =
          population(conditions, since, true)
              .statement("SELECT COUNT(*) FROM pop" + filter, jdbc)
              .param("version", version)
              .query(Long.class)
              .single();
    return new EfficiencyPage(total[0], version, rows);
  }

  @Override
  public Coverage coverage() {
    return jdbc.sql(
            """
        SELECT COUNT(*) FILTER (WHERE combat_power IS NOT NULL) AS observed, COUNT(*) AS known,
          COUNT(DISTINCT roster_key) AS accounts
        FROM characters
        """)
        .query(
            (row, index) ->
                new Coverage(
                    row.getLong("observed"), row.getLong("known"), row.getLong("accounts")))
        .single();
  }

  @Override
  public Optional<Target> target(String characterName) {
    return jdbc.sql(
            """
        SELECT character_name, server_name, class_name, position, item_level, combat_power,
          profile_refreshed_at
        FROM characters WHERE character_name = :name AND combat_power IS NOT NULL
        ORDER BY profile_refreshed_at DESC NULLS LAST LIMIT 1
        """)
        .param("name", characterName)
        .query(
            (row, index) ->
                new Target(
                    row.getString("character_name"),
                    row.getString("server_name"),
                    row.getString("class_name"),
                    row.getString("position"),
                    row.getBigDecimal("item_level"),
                    row.getBigDecimal("combat_power"),
                    instant(row.getObject("profile_refreshed_at", OffsetDateTime.class))))
        .optional();
  }

  @Override
  public boolean excluded(Target target) {
    return jdbc.sql(
            """
        SELECT EXISTS (SELECT 1 FROM public_exclusion e
          WHERE e.character_name = :name AND (e.server_name IS NULL OR e.server_name = :server))
        """)
        .param("name", target.characterName())
        .param("server", target.serverName())
        .query(Boolean.class)
        .single();
  }

  @Override
  public boolean contains(StatsConditions conditions, Instant since, Target target) {
    return population(conditions, since, true)
        .statement(
            "SELECT EXISTS (SELECT 1 FROM pop WHERE server_name = :tServer AND character_name = :tName)",
            jdbc)
        .param("tServer", target.serverName())
        .param("tName", target.characterName())
        .query(Boolean.class)
        .single();
  }

  /** 모집단 CTE와 그 바인딩 값. */
  private static Population population(StatsConditions c, Instant since) {
    return population(c, since, false);
  }

  /**
   * excludePublic이면 처리정지 목록(public_exclusion)의 캐릭터를 모집단에서 뺀다. 개인 순위·백분위(랭킹, 캐릭터 순위, percentileOf)에
   * 쓰고, 개인을 드러내지 않는 집계(요약·직업·서버·히스토그램)에는 쓰지 않는다(PM 총괄 결정 2026-10-09).
   */
  private static Population population(StatsConditions c, Instant since, boolean excludePublic) {
    var where =
        new StringBuilder(
            "c.combat_power IS NOT NULL AND c.profile_refreshed_at >= :since AND c.item_level >= :ilMin");
    var params = new HashMap<String, Object>();
    params.put("since", since.atOffset(ZoneOffset.UTC));
    params.put("ilMin", c.itemLevelMin());
    if (c.itemLevelMax() != null) {
      where.append(" AND c.item_level <= :ilMax");
      params.put("ilMax", c.itemLevelMax());
    }
    if (c.combatPowerMin() != null) {
      where.append(" AND c.combat_power >= :cpMin");
      params.put("cpMin", c.combatPowerMin());
    }
    if (c.combatPowerMax() != null) {
      where.append(" AND c.combat_power <= :cpMax");
      params.put("cpMax", c.combatPowerMax());
    }
    if (c.server() != null) {
      where.append(" AND c.server_name = :server");
      params.put("server", c.server());
    }
    if (c.role() != null) {
      where.append(" AND c.position = :role");
      params.put("role", c.role().name());
    }
    if (c.className() != null) {
      where.append(" AND c.class_name = :className");
      params.put("className", c.className());
    }
    if (c.representativeOnly())
      // 같은 원정대(같은 서버·같은 roster_key)에서 아이템 레벨이 가장 높은 캐릭터. 동률이면 이름순 첫 캐릭터.
      // 텍스트 블록은 앞 공백을 지우므로 구분 공백을 따로 붙인다.
      where
          .append(' ')
          .append(
              """
           AND (c.roster_key IS NULL OR NOT EXISTS (
             SELECT 1 FROM characters o
             WHERE o.roster_key = c.roster_key AND o.server_name = c.server_name
               AND (o.item_level > c.item_level
                    OR (o.item_level = c.item_level AND o.character_name < c.character_name))))""");
    if (excludePublic)
      where.append(
          " AND NOT EXISTS (SELECT 1 FROM public_exclusion e WHERE e.character_name = c.character_name"
              + " AND (e.server_name IS NULL OR e.server_name = c.server_name))");
    String cte =
        "WITH pop AS (SELECT c.character_name, c.server_name, c.class_name, c.item_level,"
            + " c.combat_power, c.position, c.gem_mean_level, c.bracelet_efficiency_pct,"
            + " c.bracelet_rule_version, c.ark_grid_gem_pct, c.ark_grid_rule_version,"
            + " c.efficiency_observed_at FROM characters c WHERE "
            + where
            + ") ";
    return new Population(cte, params);
  }

  private record Population(String cte, Map<String, Object> params) {
    JdbcClient.StatementSpec statement(String sql, JdbcClient jdbc) {
      return jdbc.sql(cte + sql).params(params);
    }
  }

  private static List<BigDecimal> percentiles(Array array) throws SQLException {
    if (array == null) return List.of();
    var values = (Object[]) array.getArray();
    var result = new ArrayList<BigDecimal>();
    for (var value : values)
      result.add(value == null ? null : round(new BigDecimal(value.toString())));
    return result;
  }

  private static BigDecimal round(BigDecimal value) {
    return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
