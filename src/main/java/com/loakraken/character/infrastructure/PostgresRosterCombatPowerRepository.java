package com.loakraken.character.infrastructure;

import com.loakraken.character.application.RosterCombatPowerPort;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PostgresRosterCombatPowerRepository implements RosterCombatPowerPort {
  private final JdbcClient jdbc;

  PostgresRosterCombatPowerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<String, Observed> find(String serverName, Collection<String> characterNames) {
    var result = new LinkedHashMap<String, Observed>();
    if (serverName == null || characterNames.isEmpty()) return result;
    // 전투력을 쓰는 경로(검색 스냅샷 last_synced_at·프로필 갱신 profile_refreshed_at) 중 늦은 시각이 그 값의 확인 시각이다.
    // 처리정지 목록은 기록이 없는 캐릭터와 같게 뺀다(#366 원칙, 서버를 비운 항목은 모든 서버).
    jdbc.sql(
            """
            SELECT c.character_name, c.combat_power, c.best_combat_power,
                   GREATEST(c.last_synced_at, c.profile_refreshed_at) AS observed_at
            FROM characters c
            WHERE c.server_name = :server AND c.character_name IN (:names)
              AND c.combat_power IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM public_exclusion e
                WHERE e.character_name = c.character_name
                  AND (e.server_name IS NULL OR e.server_name = c.server_name))
            """)
        .param("server", serverName)
        .param("names", characterNames)
        .query(
            (row, index) -> {
              var observedAt = row.getObject("observed_at", OffsetDateTime.class);
              result.put(
                  row.getString("character_name"),
                  new Observed(
                      row.getBigDecimal("combat_power"),
                      observedAt == null ? null : observedAt.toInstant(),
                      row.getBigDecimal("best_combat_power")));
              return null;
            })
        .list();
    return result;
  }
}
