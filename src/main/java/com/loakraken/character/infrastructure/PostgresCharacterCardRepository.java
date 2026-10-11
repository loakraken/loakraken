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
  private final JdbcClient jdbc;
  // 학습한 구성원·효과는 거의 바뀌지 않으므로 이미 쓴 것은 다시 쓰지 않는다.
  private final Set<Object> known = ConcurrentHashMap.newKeySet();

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
    int order = 0;
    for (var collectible : collectibles) {
      order++;
      if (collectible == null
          || collectible.type() == null
          || collectible.type().isBlank()
          || collectible.point() == null
          || collectible.maxPoint() == null) continue;
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
          UPDATE characters SET position = COALESCE(:position, position),
            gem_mean_level = CASE WHEN :gemsObserved THEN CAST(:gem AS NUMERIC) ELSE gem_mean_level END
          WHERE character_id = :id
          """)
          .param("position", observation.position())
          .param("gem", observation.gemMeanLevel())
          .param("gemsObserved", observation.gemsObserved())
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
            """)
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
}
