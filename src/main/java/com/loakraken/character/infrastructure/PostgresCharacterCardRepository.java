package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterCardPort;
import com.loakraken.character.domain.CardCollection;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
class PostgresCharacterCardRepository implements CharacterCardPort {
  private static final int KNOWN_LIMIT = 10_000;
  private static final String NOT_EXCLUDED = StopListSql.NOT_EXCLUDED;
  private final JdbcClient jdbc;
  // 학습한 구성원·효과는 거의 바뀌지 않으므로 이미 쓴 것은 다시 쓰지 않는다.
  private final Set<Object> known = ConcurrentHashMap.newKeySet();
  // 수집품 항목 사전: (종류, 이름) → {item_id, 공식 순서, 만점, 종류 순서, 마지막 갱신 날}(#305).
  private final Map<String, int[]> items = new ConcurrentHashMap<>();

  PostgresCharacterCardRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(timeout = 5)
  public void recordCollectibles(
      String serverName,
      String characterName,
      List<com.loakraken.character.domain.CharacterLoadout.Collectible> collectibles,
      java.time.Instant observedAt) {
    var owner =
        jdbc.sql(
                "SELECT character_id, roster_key FROM characters WHERE server_name = :server AND character_name = :name")
            .param("server", serverName)
            .param("name", characterName)
            .query(
                (row, index) ->
                    new UUID[] {row.getObject(1, UUID.class), row.getObject(2, UUID.class)})
            .optional();
    if (owner.isEmpty() || owner.get()[1] == null) return;
    var at = utc(observedAt);
    var observedTypes = new ArrayList<String>();
    int order = 0;
    for (var collectible : collectibles) {
      order++;
      if (collectible == null
          || collectible.type() == null
          || collectible.type().isBlank()
          || collectible.point() == null
          || collectible.maxPoint() == null) continue;
      observedTypes.add(collectible.type().strip());
      jdbc.sql(
              """
          INSERT INTO roster_collectible (roster_key, type, sort_order, point, max_point, observed_at, source_character_id)
          VALUES (:roster, :type, :order, :point, :max, :at, :source)
          ON CONFLICT (roster_key, type) DO UPDATE SET sort_order = EXCLUDED.sort_order, point = EXCLUDED.point,
            max_point = EXCLUDED.max_point, observed_at = EXCLUDED.observed_at,
            source_character_id = EXCLUDED.source_character_id
          WHERE roster_collectible.observed_at <= EXCLUDED.observed_at
          """)
          .param("roster", owner.get()[1])
          .param("type", collectible.type().strip())
          .param("order", order)
          .param("point", collectible.point())
          .param("max", collectible.maxPoint())
          .param("at", at)
          .param("source", owner.get()[0])
          .update();
    }
    recordItems(owner.get()[1], collectibles, at);
    // 공식 종류가 바뀌거나 사라지면 옛 종류가 합계에 섞이지 않게 이번 관측에 없는 종류를 지운다(더 새 관측은 건드리지 않음).
    if (observedTypes.isEmpty()) return;
    jdbc.sql(
            """
        DELETE FROM roster_collectible
        WHERE roster_key = :roster AND type NOT IN (:types) AND observed_at <= :at
        """)
        .param("roster", owner.get()[1])
        .param("types", observedTypes)
        .param("at", at)
        .update();
  }

  @Override
  @Transactional(timeout = 5)
  public void record(Observation observation) {
    var at = utc(observation.observedAt());
    var learned = observation.learned();
    var characterId =
        jdbc.sql(
                "SELECT character_id FROM characters WHERE server_name = :server AND character_name = :name")
            .param("server", observation.serverName())
            .param("name", observation.characterName())
            .query(UUID.class)
            .optional();
    if (characterId.isPresent()) {
      jdbc.sql(
              """
          UPDATE characters SET
            position = CASE WHEN CAST(:position AS VARCHAR) IS NOT NULL
                AND (position_observed_at IS NULL OR position_observed_at <= :at)
              THEN CAST(:position AS VARCHAR) ELSE position END,
            position_observed_at = CASE WHEN CAST(:position AS VARCHAR) IS NOT NULL
                AND (position_observed_at IS NULL OR position_observed_at <= :at)
              THEN :at ELSE position_observed_at END,
            gem_mean_level = CASE WHEN :gemsObserved THEN CAST(:gem AS NUMERIC) ELSE gem_mean_level END
          WHERE character_id = :id
          """)
          // 포지션은 더 늦은 관측이 이미 정했으면 덮지 않는다(관측 기록이 비동기라 순서가 뒤바뀔 수 있음).
          .param("position", observation.position())
          .param("at", at)
          .param("gem", observation.gemMeanLevel())
          .param("gemsObserved", observation.gemsObserved())
          .param("id", characterId.get())
          .update();
      var efficiency = observation.efficiency();
      if (efficiency != null)
        // 팔찌·젬 효율(L38·L39). 장비를 관측했을 때만 바꾸고, 더 늦은 관측이 이미 있으면 덮지 않는다.
        jdbc.sql(
                """
            UPDATE characters SET
              bracelet_efficiency_pct = CAST(:bracelet AS NUMERIC),
              bracelet_rule_version = :braceletVersion,
              ark_grid_gem_pct = CAST(:gem AS NUMERIC),
              ark_grid_rule_version = :gemVersion,
              bracelet_real_pct = CAST(:braceletReal AS NUMERIC),
              bracelet_real_rule_version = :braceletRealVersion,
              ark_grid_real_pct = CAST(:gemReal AS NUMERIC),
              ark_grid_real_rule_version = :gemRealVersion,
              efficiency_observed_at = :at
            WHERE character_id = :id
              AND (efficiency_observed_at IS NULL OR efficiency_observed_at <= :at)
            """)
            .param("bracelet", efficiency.braceletPct())
            .param("braceletVersion", efficiency.braceletRuleVersion())
            .param("gem", efficiency.arkGridGemPct())
            .param("gemVersion", efficiency.arkGridRuleVersion())
            .param("braceletReal", efficiency.braceletRealPct())
            .param("braceletRealVersion", efficiency.braceletRealRuleVersion())
            .param("gemReal", efficiency.arkGridRealPct())
            .param("gemRealVersion", efficiency.arkGridRealRuleVersion())
            .param("at", at)
            .param("id", characterId.get())
            .update();
      for (var card : observation.cards()) {
        if (card == null || card.name() == null || card.name().isBlank()) continue;
        jdbc.sql(
                """
            INSERT INTO character_card (character_id, card_name, grade, icon, awake_count, awake_total, observed_at)
            VALUES (:id, :name, :grade, :icon, :awake, :total, :at)
            ON CONFLICT (character_id, card_name) DO UPDATE SET
              grade = EXCLUDED.grade, icon = EXCLUDED.icon, awake_total = EXCLUDED.awake_total,
              awake_count = EXCLUDED.awake_count, observed_at = EXCLUDED.observed_at
            WHERE character_card.awake_count <= EXCLUDED.awake_count
              AND character_card.observed_at <= EXCLUDED.observed_at
            """)
            .param("id", characterId.get())
            .param("name", card.name())
            .param("grade", card.grade())
            .param("icon", card.icon())
            .param("awake", card.awakeCount() == null ? 0 : Math.max(0, card.awakeCount()))
            .param("total", card.awakeTotal())
            .param("at", at)
            .update();
      }
    }
    if (known.size() > KNOWN_LIMIT) known.clear();
    var written = new ArrayList<Object>();
    for (var member : learned.members()) {
      if (known.contains(member)) continue;
      written.add(member);
      jdbc.sql(
              """
          INSERT INTO card_set_member (set_name, card_name, first_observed_at) VALUES (:set, :card, :at)
          ON CONFLICT DO NOTHING
          """)
          .param("set", member.setName())
          .param("card", member.cardName())
          .param("at", at)
          .update();
    }
    for (var effect : learned.effects()) {
      if (known.contains(effect)) continue;
      written.add(effect);
      // 패치로 문구가 바뀌면 최근 관측으로 바꾼다.
      jdbc.sql(
              """
          INSERT INTO card_set_effect (set_name, pieces, awakening, description, observed_at)
          VALUES (:set, :pieces, :awakening, :description, :at)
          ON CONFLICT (set_name, pieces, awakening) DO UPDATE SET
            description = EXCLUDED.description, observed_at = EXCLUDED.observed_at
          WHERE card_set_effect.observed_at <= EXCLUDED.observed_at
          """)
          .param("set", effect.setName())
          .param("pieces", effect.pieces())
          .param("awakening", effect.awakening())
          .param("description", effect.description())
          .param("at", at)
          .update();
    }
    // 롤백되면 다음 관측에서 다시 쓰도록 커밋 후에만 기억한다.
    if (!written.isEmpty() && TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              known.addAll(written);
            }
          });
  }

  @Override
  public RosterCards rosterCards(String serverName, Collection<String> characterNames) {
    if (characterNames.isEmpty()) return new RosterCards(List.of(), 0);
    var cards =
        jdbc.sql(
                """
            SELECT DISTINCT ON (cc.card_name) cc.card_name, cc.grade, cc.icon, cc.awake_count,
                   cc.awake_total, cc.observed_at, c.character_name
            FROM character_card cc JOIN characters c ON c.character_id = cc.character_id
            WHERE c.server_name = :server AND c.character_name IN (:names)
            """
                    + NOT_EXCLUDED
                    + """

            ORDER BY cc.card_name, cc.awake_count DESC, cc.observed_at DESC
            """)
            .param("server", serverName)
            .param("names", List.copyOf(characterNames))
            .query(
                (row, index) ->
                    new CardCollection.OwnedCard(
                        row.getString("card_name"),
                        row.getString("grade"),
                        row.getString("icon"),
                        row.getInt("awake_count"),
                        row.getObject("awake_total", Integer.class),
                        row.getObject("observed_at", OffsetDateTime.class).toInstant(),
                        row.getString("character_name")))
            .list();
    int observed =
        jdbc.sql(
                """
            SELECT COUNT(DISTINCT cc.character_id)
            FROM character_card cc JOIN characters c ON c.character_id = cc.character_id
            WHERE c.server_name = :server AND c.character_name IN (:names)
            """
                    + NOT_EXCLUDED)
            .param("server", serverName)
            .param("names", List.copyOf(characterNames))
            .query(Integer.class)
            .single();
    return new RosterCards(cards, observed);
  }

  @Override
  public List<CardCollection.SetMember> members(Collection<String> cardNames) {
    if (cardNames.isEmpty()) return List.of();
    return jdbc.sql(
            "SELECT set_name, card_name FROM card_set_member WHERE card_name IN (:cards) ORDER BY set_name, card_name")
        .param("cards", List.copyOf(cardNames))
        .query((row, index) -> new CardCollection.SetMember(row.getString(1), row.getString(2)))
        .list();
  }

  @Override
  public List<CardCollection.SetEffect> effects(Collection<String> setNames) {
    if (setNames.isEmpty()) return List.of();
    return jdbc.sql(
            """
        SELECT set_name, pieces, awakening, description FROM card_set_effect
        WHERE set_name IN (:sets) ORDER BY set_name, pieces, awakening
        """)
        .param("sets", List.copyOf(setNames))
        .query(
            (row, index) ->
                new CardCollection.SetEffect(
                    row.getString(1), row.getInt(2), row.getInt(3), row.getString(4)))
        .list();
  }

  @Override
  public Map<String, Instant> refreshedAt(String serverName, Collection<String> characterNames) {
    var result = new HashMap<String, Instant>();
    if (characterNames.isEmpty()) return result;
    jdbc.sql(
            """
        SELECT character_name, profile_refreshed_at FROM characters
        WHERE server_name = :server AND character_name IN (:names) AND profile_refreshed_at IS NOT NULL
        """)
        .param("server", serverName)
        .param("names", List.copyOf(characterNames))
        .query(
            (ResultSet row) -> {
              result.put(row.getString(1), row.getObject(2, OffsetDateTime.class).toInstant());
            });
    return result;
  }

  @Override
  public String position(String serverName, String characterName) {
    return jdbc.sql(
            "SELECT position FROM characters WHERE server_name = :server AND character_name = :name")
        .param("server", serverName)
        .param("name", characterName)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private static OffsetDateTime utc(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  /**
   * 수집품 항목(#305): 사전에 없는 항목을 더하고, 원정대가 가진 항목(Point > 0)을 (item_id, point) 배열 한 행으로 덮어쓴다. 사전은 거의 바뀌지
   * 않으므로 메모리에 두고 모르는 이름이나 순서·만점이 바뀐 항목만 DB에 쓴다.
   */
  private void recordItems(
      UUID rosterKey,
      List<com.loakraken.character.domain.CharacterLoadout.Collectible> collectibles,
      OffsetDateTime at) {
    // 같은 종류 안에 같은 이름이 두 번 와도 원정대를 두 번 세지 않게 (type, name) 하나로 합친다(큰 point).
    var held = new java.util.LinkedHashMap<Short, Integer>();
    boolean any = false;
    int typeOrder = 0;
    for (var collectible : collectibles) {
      typeOrder++;
      if (collectible == null || collectible.type() == null || collectible.type().isBlank())
        continue;
      String type = collectible.type().strip();
      int order = 0;
      for (var item : collectible.items()) {
        order++;
        if (item == null || item.name() == null || item.name().isBlank() || item.maxPoint() == null)
          continue;
        String name = item.name().strip();
        if (type.length() > 32 || name.length() > 64) continue;
        any = true;
        short id = itemId(type, name, typeOrder, order, item.maxPoint(), at);
        int point = item.point() == null ? 0 : item.point();
        if (point > 0) held.merge(id, Math.min(point, Short.MAX_VALUE), Math::max);
      }
    }
    // 항목 목록이 없는 응답(이전 형식·해석 실패)은 원정대 행을 비우지 않는다.
    if (!any) return;
    var ids = new StringBuilder();
    var points = new StringBuilder();
    for (var entry : held.entrySet()) {
      if (!ids.isEmpty()) {
        ids.append(',');
        points.append(',');
      }
      ids.append(entry.getKey());
      points.append(entry.getValue());
    }
    jdbc.sql(
            """
        INSERT INTO roster_collectible_item (roster_key, item_ids, points, observed_at)
        VALUES (:roster, CAST(:ids AS SMALLINT[]), CAST(:points AS SMALLINT[]), :at)
        ON CONFLICT (roster_key) DO UPDATE SET item_ids = EXCLUDED.item_ids, points = EXCLUDED.points,
          observed_at = EXCLUDED.observed_at
        WHERE roster_collectible_item.observed_at <= EXCLUDED.observed_at
        """)
        .param("roster", rosterKey)
        .param("ids", "{" + ids + "}")
        .param("points", "{" + points + "}")
        .param("at", at)
        .update();
  }

  private short itemId(
      String type, String name, int typeOrder, int order, int maxPoint, OffsetDateTime at) {
    String key = type + '\u0000' + name;
    var cached = items.get(key);
    // 순서·만점이 같고 오늘 이미 last_seen_at을 갱신했으면 DB에 가지 않는다(사라진 항목 판단은 하루 단위면 충분).
    int day = (int) at.toLocalDate().toEpochDay();
    if (cached != null
        && cached[1] == order
        && cached[2] == maxPoint
        && cached[3] == typeOrder
        && cached[4] == day) return (short) cached[0];
    short id =
        jdbc.sql(
                """
            INSERT INTO collectible_item (type, name, type_order, sort_order, max_point, last_seen_at)
            VALUES (:type, :name, :typeOrder, :order, :max, :at)
            ON CONFLICT (type, name) DO UPDATE SET type_order = EXCLUDED.type_order,
              sort_order = EXCLUDED.sort_order, max_point = EXCLUDED.max_point,
              last_seen_at = GREATEST(collectible_item.last_seen_at, EXCLUDED.last_seen_at)
            RETURNING item_id
            """)
            .param("type", type)
            .param("name", name)
            .param("typeOrder", typeOrder)
            .param("order", order)
            .param("max", Math.min(maxPoint, Short.MAX_VALUE))
            .param("at", at)
            .query(Short.class)
            .single();
    if (items.size() > KNOWN_LIMIT) items.clear();
    items.put(key, new int[] {id, order, maxPoint, typeOrder, day});
    return id;
  }
}
