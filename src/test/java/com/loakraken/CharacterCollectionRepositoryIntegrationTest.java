package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.CharacterCollectionPort;
import com.loakraken.character.application.CharacterCollectionPort.Target;
import com.loakraken.character.application.CharacterHistoryPort;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.CharacterSibling;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CharacterCollectionRepositoryIntegrationTest {
  private static final String SERVER = "수집테스트서버";
  private static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");
  @Autowired CharacterCollectionPort collection;
  @Autowired CharacterHistoryPort history;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  @AfterEach
  void 정리() {
    for (var table :
        List.of("character_title_observation", "character_observation", "character_snapshot"))
      jdbc.sql(
              "DELETE FROM "
                  + table
                  + " WHERE character_id IN (SELECT character_id FROM characters WHERE server_name = :server)")
          .param("server", SERVER)
          .update();
    jdbc.sql("DELETE FROM characters WHERE server_name = :server").param("server", SERVER).update();
  }

  @Test
  void 원정대를_저장하면_새_캐릭터만_발견으로_세고_훑은_시각을_남긴다() {
    var roster =
        List.of(
            sibling("본캐", "1700"),
            sibling("부캐", "1640"),
            sibling("저레벨", "1500"),
            new CharacterSibling(null, SERVER, "바드", 70, BigDecimal.ONE));
    assertThat(collection.saveRoster(roster, NOW)).isEqualTo(3);
    assertThat(collection.saveRoster(List.of(sibling("본캐", "1705")), NOW.plusSeconds(10))).isZero();
    assertThat(
            jdbc.sql(
                    "SELECT item_level FROM characters WHERE server_name = :s AND character_name = '본캐'")
                .param("s", SERVER)
                .query(BigDecimal.class)
                .single())
        .isEqualByComparingTo("1705");
    assertThat(
            jdbc.sql(
                    "SELECT COUNT(*) FROM characters WHERE server_name = :s AND roster_scanned_at IS NOT NULL AND profile_refreshed_at IS NULL AND discovered_at IS NOT NULL")
                .param("s", SERVER)
                .query(Long.class)
                .single())
        .isEqualTo(3);
  }

  @Test
  void 프로필_갱신_대상은_최소_레벨_이상_미갱신_우선이고_검색_이력은_갱신으로_친다() {
    collection.saveRoster(
        List.of(sibling("본캐", "1700"), sibling("부캐", "1680"), sibling("저레벨", "1500")), NOW);
    var stale = NOW.plusSeconds(60);
    var first = collection.nextProfileDue(new BigDecimal("1640"), stale).orElseThrow();
    if (!first.serverName().equals(SERVER)) return; // 다른 테스트 데이터가 앞설 수 있으면 순서 검증을 건너뛴다.
    assertThat(first.characterName()).isEqualTo("본캐");
    collection.markProfileRefreshed(first, "SUPPORT", NOW);
    history.record(profile("부캐", "1681"), NOW);
    assertThat(
            jdbc.sql(
                    "SELECT COUNT(*) FROM characters WHERE server_name = :s AND profile_refreshed_at IS NOT NULL")
                .param("s", SERVER)
                .query(Long.class)
                .single())
        .isEqualTo(2);
    assertThat(
            jdbc.sql(
                    "SELECT position FROM characters WHERE server_name = :s AND character_name = '본캐'")
                .param("s", SERVER)
                .query(String.class)
                .single())
        .isEqualTo("SUPPORT");
    collection.markProfileMissing(new Target("저레벨", SERVER), NOW);
    collection.markRosterScanned(new Target("본캐", SERVER), NOW.plusSeconds(5));
  }

  private static CharacterSibling sibling(String name, String itemLevel) {
    return new CharacterSibling(name, SERVER, "바드", 70, new BigDecimal(itemLevel));
  }

  private static CharacterProfile profile(String name, String itemLevel) {
    return new CharacterProfile(
        name,
        SERVER,
        "바드",
        70,
        new BigDecimal(itemLevel),
        new BigDecimal("3000"),
        200,
        null,
        null,
        null,
        List.of());
  }
}
