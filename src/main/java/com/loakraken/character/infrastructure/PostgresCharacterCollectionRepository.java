package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterCollectionPort;
import com.loakraken.character.domain.CharacterSibling;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class PostgresCharacterCollectionRepository implements CharacterCollectionPort {
  private final JdbcClient jdbc;

  PostgresCharacterCollectionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Target> nextProfileDue(BigDecimal minItemLevel, Instant staleBefore) {
    return jdbc.sql(
            """
        SELECT character_name, server_name FROM characters
        WHERE item_level >= :min AND (profile_checked_at IS NULL OR profile_checked_at < :stale)
          AND NOT EXISTS (
            SELECT 1 FROM public_exclusion e
            WHERE e.character_name = characters.character_name
              AND (e.server_name IS NULL OR e.server_name = characters.server_name))
        ORDER BY profile_checked_at NULLS FIRST, item_level DESC, character_name
        LIMIT 1
        """)
        .param("min", minItemLevel)
        .param("stale", utc(staleBefore))
        .query((row, index) -> new Target(row.getString(1), row.getString(2)))
        .optional();
  }

  @Override
  public Optional<Target> nextRosterDue(Instant staleBefore) {
    return jdbc.sql(
            """
        SELECT character_name, server_name FROM characters
        WHERE (roster_scanned_at IS NULL OR roster_scanned_at < :stale)
          AND NOT EXISTS (
            SELECT 1 FROM public_exclusion e
            WHERE e.character_name = characters.character_name
              AND (e.server_name IS NULL OR e.server_name = characters.server_name))
        ORDER BY roster_scanned_at NULLS FIRST, item_level DESC, character_name
        LIMIT 1
        """)
        .param("stale", utc(staleBefore))
        .query((row, index) -> new Target(row.getString(1), row.getString(2)))
        .optional()
        // 원정대 키가 없는 캐릭터(V7 이전에 훑은 원정대)는 한 번 더 훑는다. 부분 인덱스로 따로 찾는다.
        .or(
            () ->
                jdbc.sql(
                        """
                    SELECT character_name, server_name FROM characters WHERE roster_key IS NULL
                      AND NOT EXISTS (
                        SELECT 1 FROM public_exclusion e
                        WHERE e.character_name = characters.character_name
                          AND (e.server_name IS NULL OR e.server_name = characters.server_name))
                    ORDER BY item_level DESC, character_name LIMIT 1
                    """)
                    .query((row, index) -> new Target(row.getString(1), row.getString(2)))
                    .optional());
  }

  @Override
  @Transactional(timeout = 5)
  public int saveRoster(List<CharacterSibling> siblings, Instant now) {
    int discovered = 0;
    var rosterKey = rosterKey(siblings);
    // 처리정지 목록(B9 ③) 캐릭터는 원정대에서 보여도 새로 기록하지 않는다. 이미 있는 행도 갱신하지 않는다.
    var excluded = excluded(siblings);
    for (var sibling : siblings) {
      if (sibling != null
          && excluded.contains(sibling.serverName() + "|" + sibling.characterName())) continue;
      if (sibling == null
          || sibling.characterName() == null
          || sibling.serverName() == null
          || sibling.className() == null
          || sibling.itemLevel() == null) continue;
      // 새 캐릭터는 프로필 갱신 전까지 전투력·포지션이 비어 있다. 기존 캐릭터는 아이템 레벨·직업만 원정대 값으로 맞춘다.
      Boolean inserted =
          jdbc.sql(
                  """
          INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
                                  last_synced_at, discovered_at, roster_scanned_at, roster_key)
          VALUES (:id, :name, :server, :className, :itemLevel, :now, :now, :now, :rosterKey)
          ON CONFLICT (server_name, character_name) DO UPDATE SET
            class_name = EXCLUDED.class_name, item_level = EXCLUDED.item_level,
            roster_scanned_at = EXCLUDED.roster_scanned_at, roster_key = EXCLUDED.roster_key
          RETURNING (xmax = 0)
          """)
              .param("id", UUID.randomUUID())
              .param("name", sibling.characterName())
              .param("server", sibling.serverName())
              .param("className", sibling.className())
              .param("itemLevel", sibling.itemLevel())
              .param("now", utc(now))
              .param("rosterKey", rosterKey)
              .query(Boolean.class)
              .single();
      if (Boolean.TRUE.equals(inserted)) discovered++;
    }
    return discovered;
  }

  /** 원정대 중 처리정지 목록에 있는 "서버|이름". 서버가 비어 있는 제외는 모든 서버에 적용한다. */
  private java.util.Set<String> excluded(List<CharacterSibling> siblings) {
    var names =
        siblings.stream()
            .filter(s -> s != null && s.characterName() != null)
            .map(CharacterSibling::characterName)
            .distinct()
            .toList();
    if (names.isEmpty()) return java.util.Set.of();
    var rows =
        jdbc.sql(
                "SELECT character_name, server_name FROM public_exclusion WHERE character_name IN (:names)")
            .param("names", names)
            .query((row, index) -> new String[] {row.getString(1), row.getString(2)})
            .list();
    var result = new HashSet<String>();
    for (var sibling : siblings) {
      if (sibling == null || sibling.characterName() == null) continue;
      for (var row : rows)
        if (row[0].equals(sibling.characterName())
            && (row[1] == null || row[1].equals(sibling.serverName())))
          result.add(sibling.serverName() + "|" + sibling.characterName());
    }
    return result;
  }

  /** 원정대 캐릭터 중 이미 키가 있으면 그 키를 쓰고, 없으면 새로 만든다. */
  private UUID rosterKey(List<CharacterSibling> siblings) {
    var pairs = new HashSet<String>();
    for (var sibling : siblings)
      if (sibling != null && sibling.characterName() != null && sibling.serverName() != null)
        pairs.add(sibling.serverName() + "|" + sibling.characterName());
    if (pairs.isEmpty()) return UUID.randomUUID();
    var names =
        siblings.stream()
            .filter(s -> s != null && s.characterName() != null)
            .map(CharacterSibling::characterName)
            .distinct()
            .toList();
    return jdbc
        .sql(
            """
        SELECT server_name, character_name, roster_key FROM characters
        WHERE roster_key IS NOT NULL AND character_name IN (:names)
        ORDER BY roster_key
        """)
        .param("names", names)
        .query(
            (row, index) ->
                pairs.contains(row.getString(1) + "|" + row.getString(2))
                    ? row.getObject(3, UUID.class)
                    : null)
        .list()
        .stream()
        .filter(Objects::nonNull)
        .findFirst()
        .orElseGet(UUID::randomUUID);
  }

  @Override
  public void markProfileRefreshed(Target target, String position, Instant now) {
    jdbc.sql(
            """
        UPDATE characters SET profile_refreshed_at = :now, profile_checked_at = :now, position = :position
        WHERE server_name = :server AND character_name = :name
        """)
        .param("now", utc(now))
        .param("position", position)
        .param("server", target.serverName())
        .param("name", target.characterName())
        .update();
  }

  @Override
  public void markProfileMissing(Target target, Instant now) {
    jdbc.sql(
            // 못 찾음·해석 실패는 시도 시각만 남긴다. 성공 관측 시각(profile_refreshed_at)이 그대로라 통계에서 기간이 지나면 빠진다.
            "UPDATE characters SET profile_checked_at = :now WHERE server_name = :server AND character_name = :name")
        .param("now", utc(now))
        .param("server", target.serverName())
        .param("name", target.characterName())
        .update();
  }

  @Override
  public boolean rosterKnown(String characterName) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM characters WHERE character_name = :name AND roster_scanned_at IS NOT NULL)")
        .param("name", characterName)
        .query(Boolean.class)
        .single();
  }

  @Override
  public void markRosterScanned(Target target, Instant now) {
    // 원정대를 찾지 못했거나 목록에 자신이 없던 캐릭터도 자기 키를 받아, 키가 없다는 이유로 다시 고르지 않는다.
    jdbc.sql(
            """
        UPDATE characters SET roster_scanned_at = :now, roster_key = COALESCE(roster_key, gen_random_uuid())
        WHERE server_name = :server AND character_name = :name
        """)
        .param("now", utc(now))
        .param("server", target.serverName())
        .param("name", target.characterName())
        .update();
  }

  private static OffsetDateTime utc(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }
}
