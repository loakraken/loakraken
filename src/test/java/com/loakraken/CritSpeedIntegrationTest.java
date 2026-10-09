package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CritSpeedIntegrationTest {
  private static final String NAME = "치적계산캐릭터";
  @LocalServerPort private int port;
  @Autowired private StringRedisTemplate redis;
  @Autowired private JdbcClient jdbc;
  @MockitoBean private CharacterDetailsPort detailsPort;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    redis.delete("character:v11:" + NAME + ":summary");
    redis.delete("character:v11:" + NAME + ":armory");
    redis.delete("character:v1:" + NAME + ":siblings");
    redis.delete("character:v1:" + NAME + ":missing");
    for (var table :
        List.of("character_title_observation", "character_observation", "character_snapshot"))
      jdbc.sql(
              "DELETE FROM "
                  + table
                  + " WHERE character_id IN (SELECT character_id FROM characters WHERE character_name = :name)")
          .param("name", NAME)
          .update();
    jdbc.sql("DELETE FROM characters WHERE character_name = :name").param("name", NAME).update();
  }

  @Test
  void 관측과_가정을_분리한_치적과_필요_스탯을_반환하고_캐시를_공유한다() throws Exception {
    when(detailsPort.fetchArmory(anyString())).thenReturn(character());
    when(detailsPort.fetch(anyString())).thenReturn(character());
    var body =
        """
        {"characterName":" %s ","assumptions":{"backAttack":true,"extraAttackSpeed":14,"extraMoveSpeed":14}}
        """
            .formatted(NAME);
    var response = post(body, "application/json");
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    assertThat(json.path("code").asText()).isEqualTo("CHARACTER_CRIT_SPEED_SUCCESS");
    assertThat(json.at("/data/ruleVersion").asText()).isEqualTo("crit-speed-v3");
    assertThat(json.at("/data/characterName").asText()).isEqualTo(NAME);
    assertThat(json.at("/data/crit/observedRate").decimalValue()).isEqualByComparingTo("25.71");
    assertThat(json.at("/data/crit/assumedRate").decimalValue()).isEqualByComparingTo("10");
    assertThat(json.at("/data/crit/targets/0/key").asText()).isEqualTo("CRIT_100");
    assertThat(json.at("/data/crit/bluntThorn/source").asText()).isEqualTo("REFERENCE_LEVEL_2");
    assertThat(json.at("/data/speed/supersonic/requiredSwiftness").asInt()).isEqualTo(2290);
    assertThat(json.at("/data/speed/swiftnessPerPercent").decimalValue())
        .isEqualByComparingTo("58.22");
    assertThat(json.at("/data/crit/critStatPerPercent").decimalValue())
        .isEqualByComparingTo("27.944");
    assertThat(json.at("/data/speed/swiftnessTable/0/swiftness").asInt()).isZero();
    assertThat(json.at("/data/speed/swiftnessTable/0/attack").decimalValue())
        .isEqualByComparingTo("14");
    assertThat(json.at("/data/sources/0/kind").asText()).isEqualTo("OBSERVED");
    assertThat(json.at("/data/sources/0/text").asText()).contains("20.61%");
    assertThat(json.at("/data/limitations").size()).isPositive();
    assertThat(post(body, "application/json").statusCode()).isEqualTo(200);
    verify(detailsPort, times(1)).fetchArmory(NAME);
    verify(detailsPort, never()).fetchSiblings(anyString());
    ApiContractFixture.write("crit-speed.json", response.body());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"characterName\":\"\"}",
        "{\"characterName\":\"a-b\"}",
        "{\"characterName\":\"모험가\",\"assumptions\":{\"critSynergyCount\":4}}",
        "{\"characterName\":\"모험가\",\"assumptions\":{\"extraCritRate\":-1}}",
        "{\"characterName\":\"모험가\",\"assumptions\":{\"extraAttackSpeed\":100.001}}",
        "{\"characterName\":\"모험가\",\"assumptions\":{\"extraMoveSpeed\":-100.5}}",
        "{broken"
      })
  void 잘못된_요청은_조회_전에_400으로_거절한다(String body) throws Exception {
    var response = post(body, "application/json");
    assertThat(response.statusCode()).as(body).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_REQUEST");
    verifyNoInteractions(detailsPort);
  }

  @Test
  void JSON_외_요청은_415를_반환한다() throws Exception {
    assertThat(post("{\"characterName\":\"모험가\"}", "text/plain").statusCode()).isEqualTo(415);
    verifyNoInteractions(detailsPort);
  }

  private FetchedCharacter character() {
    var profile =
        new CharacterProfile(
            NAME,
            "카단",
            "블레이드",
            70,
            new java.math.BigDecimal("1770"),
            null,
            null,
            null,
            null,
            null,
            List.of(
                new CharacterProfile.Stat("치명", "576", List.of("치명타 적중률이 20.61% 증가합니다.")),
                new CharacterProfile.Stat(
                    "신속", "77", List.of("공격 속도가 1.32% 증가합니다.", "이동 속도가 1.32% 증가합니다."))));
    var ring =
        new CharacterLoadout.EquipmentDetail(
            null,
            null,
            null,
            4,
            null,
            List.of(),
            List.of(),
            List.of(new CharacterLoadout.PolishEffect("치명타 적중률", "+1.55%", null)),
            List.of());
    var loadout =
        new CharacterLoadout(
            List.of(
                new CharacterLoadout.Equipment("반지", "반지1", null, "고대", ring),
                new CharacterLoadout.Equipment("반지", "반지2", null, "고대", ring)),
            null,
            null,
            null,
            new CharacterLoadout.ArkPassive(
                "아크패시브",
                true,
                List.of(),
                List.of(
                    new CharacterLoadout.PassiveEffect(
                        "진화", "진화 4티어 달인 Lv.1", null, "달인 : 치명타 적중률 +0.4% / 추가 피해 +1.7%, 최대 5중첩"))),
            null);
    return new FetchedCharacter(CharacterTestFixture.details(profile, loadout, null), "{}", null);
  }

  private HttpResponse<String> post(String body, String type) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(
                  URI.create("http://localhost:" + port + "/api/v1/characters/crit-speed"))
              .header("Content-Type", type)
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }
}
