package com.loakraken.character.infrastructure;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loakraken.character.application.EquipmentSummaryPort;
import com.loakraken.character.domain.EquipmentSummary;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 장비 요약 구간 행 저장(#146). features는 키 정렬 직렬화로 저장하고, 그 문자열과 공식 전투력으로 SHA-256을 만든다. 순서만 바뀐 관측은 같은 해시라 새
 * 행이 생기지 않는다.
 */
@Repository
class PostgresEquipmentSummaryRepository implements EquipmentSummaryPort {
  static final JsonMapper CANONICAL =
      JsonMapper.builder()
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .addMixIn(EquipmentSummary.Weapon.class, WeaponMixIn.class)
          .build();
  private final JdbcClient jdbc;

  PostgresEquipmentSummaryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(timeout = 5)
  public Outcome record(
      String serverName, String characterName, EquipmentSummary summary, Instant observedAt) {
    var characterId =
        jdbc.sql(
                "SELECT character_id FROM characters WHERE server_name = :server AND character_name = :name")
            .param("server", serverName)
            .param("name", characterName)
            .query(UUID.class)
            .optional();
    if (characterId.isEmpty()) return Outcome.SKIPPED;
    String features = CANONICAL.writeValueAsString(summary.features());
    String hash = hash(features, summary);
    var at = observedAt.atOffset(ZoneOffset.UTC);
    // 같은 캐릭터를 동시에 쓰는 다른 인스턴스와 겹치지 않도록 요약 쪽 잠금(트랜잭션 advisory lock)을 건다. characters 행을 잠그지 않아
    // 같은 캐릭터 검색의 관측 upsert를 기다리게 하지 않는다(PM-백엔드 #165 P2).
    jdbc.sql(
            "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(CAST(:id AS text), 146))) lock")
        .param("id", characterId.get())
        .query(Integer.class)
        .single();
    var latest =
        jdbc.sql(
                """
            SELECT summary_id, features_hash FROM character_equipment_summary
            WHERE character_id = :id ORDER BY observed_at DESC, summary_id DESC LIMIT 1
            """)
            .param("id", characterId.get())
            .query((row, index) -> new Latest(row.getLong(1), row.getString(2)))
            .optional();
    if (latest.isPresent() && latest.get().hash().equals(hash)) {
      jdbc.sql(
              """
          UPDATE character_equipment_summary
          SET last_confirmed_at = GREATEST(last_confirmed_at, :at)
          WHERE summary_id = :summary
          """)
          .param("at", at)
          .param("summary", latest.get().id())
          .update();
      return Outcome.CONFIRMED;
    }
    jdbc.sql(
            """
        INSERT INTO character_equipment_summary
          (character_id, observed_at, last_confirmed_at, schema_version, combat_power, item_level,
           character_level, class_name, ark_passive_title, features, features_hash)
        VALUES (:id, :at, :at, :schema, :cp, :itemLevel, :level, :className, :title,
                CAST(:features AS JSONB), :hash)
        """)
        .param("id", characterId.get())
        .param("at", at)
        .param("schema", EquipmentSummary.SCHEMA_VERSION)
        .param("cp", summary.combatPower())
        .param("itemLevel", summary.itemLevel())
        .param("level", summary.characterLevel())
        .param("className", summary.className())
        .param("title", summary.arkPassiveTitle())
        .param("features", features)
        .param("hash", hash)
        .update();
    return Outcome.CREATED;
  }

  /** 정렬 직렬화한 features + 스키마 버전 + 공식 전투력. 둘 중 하나라도 바뀌면 새 행이다. */
  static String hash(String canonicalFeatures, EquipmentSummary summary) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(EquipmentSummary.SCHEMA_VERSION.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '|');
      digest.update(canonicalFeatures.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '|');
      var cp = summary.combatPower();
      digest.update(
          (cp == null ? "null" : cp.stripTrailingZeros().toPlainString())
              .getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record Latest(long id, String hash) {}

  /** 에스더 무기 값이 없으면 키를 빼서, 필드를 더하기 전에 저장한 요약과 해시가 같게 둔다. */
  abstract static class WeaponMixIn {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract EquipmentSummary.Esther esther();
  }
}
