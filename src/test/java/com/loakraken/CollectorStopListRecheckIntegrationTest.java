package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterCollectorService;
import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 통계 표본 수집기: 대상을 고른 뒤 공식 응답을 받는 사이에 처리정지 목록에 든 캐릭터는 아무것도 남기지 않는다(관측 이력·카드·갱신 표시). 대상 고르기는 목록을 이미
 * 빼므로, 받는 동안 목록에 드는 경우만 남는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "stats.collector.enabled=true",
      "stats.collector.initial-delay=1h",
      "stats.collector.interval=1h",
      "lostark.api-key=test-key"
    })
class CollectorStopListRecheckIntegrationTest {
  private static final String NAME = "수집중정지캐";
  private static final String SERVER = "수집정지서버";
  @Autowired CharacterCollectorService collector;
  @Autowired JdbcClient jdbc;
  @MockitoBean CharacterDetailsPort detailsPort;

  @BeforeEach
  @AfterEach
  void 정리() {
    for (var table :
        List.of(
            "character_card",
            "character_title_observation",
            "character_observation",
            "character_snapshot"))
      jdbc.sql(
              "DELETE FROM "
                  + table
                  + " WHERE character_id IN (SELECT character_id FROM characters WHERE server_name = :s)")
          .param("s", SERVER)
          .update();
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name = :n").param("n", NAME).update();
  }

  @Test
  void 받는_동안_처리정지_목록에_든_캐릭터는_관측_이력을_남기지_않는다() {
    // 다음 프로필 대상이 되도록(확인한 적 없음, 아이템 레벨 최고).
    jdbc.sql(
            """
            INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
              last_synced_at, profile_refreshed_at)
            VALUES (:id, :n, :s, '바드', 9999, :at, :at)
            """)
        .param("id", UUID.randomUUID())
        .param("n", NAME)
        .param("s", SERVER)
        .param("at", OffsetDateTime.now().minusDays(30))
        .update();
    // 공식 응답을 받는 사이에 목록에 든다.
    when(detailsPort.fetchArmory(anyString()))
        .thenAnswer(
            invocation -> {
              jdbc.sql(
                      "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES (:n, :s, 'PRIVACY_STOP')")
                  .param("n", NAME)
                  .param("s", SERVER)
                  .update();
              return character();
            });

    assertThat(collector.tick()).isEqualTo(CharacterCollectorService.Task.PROFILE);

    assertThat(
            jdbc.sql(
                    "SELECT COUNT(*) FROM character_observation o JOIN characters c USING (character_id) WHERE c.server_name = :s")
                .param("s", SERVER)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql(
                    "SELECT COUNT(*) FROM character_snapshot o JOIN characters c USING (character_id) WHERE c.server_name = :s")
                .param("s", SERVER)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql(
                    "SELECT COUNT(*) FROM character_card o JOIN characters c USING (character_id) WHERE c.server_name = :s")
                .param("s", SERVER)
                .query(Long.class)
                .single())
        .isZero();
  }

  private static FetchedCharacter character() {
    var profile =
        new CharacterProfile(
            NAME,
            SERVER,
            "바드",
            70,
            new BigDecimal("1700"),
            new BigDecimal("3000"),
            null,
            null,
            null,
            null,
            List.of());
    var loadout =
        new CharacterLoadout(
            List.of(),
            null,
            null,
            new CharacterLoadout.Cards(
                List.of(new CharacterLoadout.Card(0, "샨디", "icon", 5, 5, "전설")), List.of()),
            null,
            null);
    return new FetchedCharacter(CharacterTestFixture.details(profile, loadout, null), "{}", null);
  }
}
