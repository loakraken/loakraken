package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** 아이템 툴팁 나머지 줄(#227): 캐시만 읽고 공식 호출이 없으며 검색 응답과 키로 잇는다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CharacterItemTooltipIntegrationTest {
  private static final String SERVER = "툴팁서버";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @MockitoBean CharacterDetailsPort detailsPort;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '툴팁%'").update();
    var keys = redis.keys("character:v*툴팁*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 캐시가_없으면_404이고_공식_호출이_없다() throws Exception {
    var response = get("/api/v1/characters/item-tooltips", "툴팁없음");
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("CHARACTER_NOT_CACHED");
    verifyNoInteractions(detailsPort);
  }

  @Test
  void 검색_뒤에는_캐시에서_나머지_줄을_주고_검색_응답에는_extraLines가_없다() throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> fetched(invocation.getArgument(0)));
    var search = get("/api/v1/characters/search", "툴팁캐시");
    assertThat(search.statusCode()).isEqualTo(200);
    var loadout = mapper.readTree(search.body()).at("/data/loadout");
    // 짧은 필드는 검색 응답에, 나머지 줄은 따로(PM 11:36·11:37 분리안).
    assertThat(loadout.at("/equipment/1/detail/titleLine").asText()).isEqualTo("고대 귀걸이");
    assertThat(loadout.at("/equipment/1/detail/tradeLines/0").asText()).isEqualTo("캐릭터 귀속됨");
    assertThat(search.body()).doesNotContain("extraLines", "내구도");

    var response = get("/api/v1/characters/item-tooltips", "툴팁캐시");
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("character-item-tooltips.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("hidden").asBoolean()).isFalse();
    // 검색 응답과 같은 캐시 시각이면 순번이 맞다(PM-프론트 11:49 P2).
    assertThat(data.path("fetchedAt").asText())
        .isEqualTo(mapper.readTree(search.body()).at("/data/fetchedAt").asText());
    // 줄이 없는 장비(0번)는 빼고, 순번·type으로 검색 응답 항목과 잇는다.
    assertThat(data.at("/equipment").size()).isEqualTo(1);
    assertThat(data.at("/equipment/0/index").asInt()).isEqualTo(1);
    assertThat(data.at("/equipment/0/type").asText()).isEqualTo("귀걸이");
    assertThat(data.at("/equipment/0/extraLines/0").asText()).isEqualTo("내구도 62 / 175");
    assertThat(data.at("/gems/0/slot").asInt()).isEqualTo(3);
    assertThat(data.at("/gems/0/extraLines/0").asText()).isEqualTo("보석 레벨 6");
    assertThat(data.at("/cards/0/extraLines/0").asText()).isEqualTo("카드 설명");
    assertThat(data.at("/skills/0/name").asText()).isEqualTo("테스트 스킬");
    assertThat(data.at("/skills/0/runeExtraLines/0").asText()).isEqualTo("룬 설명");
    assertThat(data.at("/skills/0/titleLines/1").asText()).isEqualTo("재사용 대기시간 5초");
    assertThat(data.at("/skills/0/infoLines/2").asText()).isEqualTo("마나 211 소모");
    assertThat(data.at("/skills/0/tripodOptions").size()).isEqualTo(2);
    assertThat(data.at("/skills/0/tripodOptions/1/name").asText()).isEqualTo("급소 타격");
    assertThat(data.at("/skills/0/tripodOptions/1/selected").asBoolean()).isFalse();
    assertThat(data.at("/skills/0/tripodOptions/0/selected").asBoolean()).isTrue();
    // 아크 그리드 코어·젬 툴팁 줄(#288): 검색 응답 slots[].index·gems[].index로 잇는다.
    assertThat(data.at("/arkGrid/0/index").asInt()).isEqualTo(0);
    assertThat(data.at("/arkGrid/0/titleLine").asText()).isEqualTo("유물 아크 그리드 코어");
    assertThat(data.at("/arkGrid/0/extraLines/1").asText()).isEqualTo("분해불가");
    assertThat(data.at("/arkGrid/0/gems/0/tradeLines/0").asText()).isEqualTo("가공 완료");
    assertThat(search.body()).doesNotContain("가공 완료");
    verify(detailsPort, times(1)).fetch(anyString());
  }

  @Test
  void 처리정지_목록_이름도_캐시가_없으면_일반_이름과_같은_404다() throws Exception {
    // #363: 처리정지 여부를 응답으로 드러내지 않는다(예전 hidden=true 응답 없음).
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('툴팁제외', NULL, 'USER_REQUEST')")
        .update();
    var stopped = get("/api/v1/characters/item-tooltips", "툴팁제외");
    var unknown = get("/api/v1/characters/item-tooltips", "툴팁없는이름");
    assertThat(stopped.statusCode()).isEqualTo(404).isEqualTo(unknown.statusCode());
    assertThat(mapper.readTree(stopped.body()).path("code").asText())
        .isEqualTo(mapper.readTree(unknown.body()).path("code").asText());
    verifyNoInteractions(detailsPort);
  }

  private FetchedCharacter fetched(String name) {
    var profile =
        new CharacterProfile(
            name,
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
    var earring =
        new CharacterLoadout.Equipment(
            "귀걸이",
            "도래한 결전의 귀걸이",
            null,
            "고대",
            new CharacterLoadout.EquipmentDetail(
                null,
                90,
                null,
                4,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "고대 귀걸이",
                List.of("캐릭터 귀속됨", "거래 3회 가능"),
                null,
                List.of("내구도 62 / 175")));
    var weapon = new CharacterLoadout.Equipment("무기", "테스트 무기", null, "고대", null);
    var gems =
        new CharacterLoadout.Gems(
            List.of(new CharacterLoadout.Gem(3, "6레벨 광휘의 보석", null, 6, "전설", List.of("보석 레벨 6"))),
            null);
    var cards =
        new CharacterLoadout.Cards(
            List.of(new CharacterLoadout.Card(0, "샨디", null, 5, 5, "전설", List.of("카드 설명"))),
            List.of());
    var skill =
        new CharacterLoadout.Skill(
            "테스트 스킬",
            null,
            12,
            null,
            null,
            null,
            List.of(),
            new CharacterLoadout.Rune("속행", null, "전설", "효과", List.of("룬 설명")),
            List.of(),
            null,
            null,
            List.of("일반", "재사용 대기시간 5초", "[일반 스킬]"),
            List.of("PvE", "스킬 레벨 12", "마나 211 소모"),
            List.of(
                new CharacterLoadout.TripodOption(0, 1, "약점 노출", null, true),
                new CharacterLoadout.TripodOption(0, 2, "급소 타격", null, false)));
    var arkGrid =
        new CharacterLoadout.ArkGrid(
            List.of(
                new CharacterLoadout.GridSlot(
                    0,
                    null,
                    "질서의 해 코어",
                    17,
                    "유물",
                    List.of(
                        new CharacterLoadout.GridGem(
                            0,
                            null,
                            true,
                            "전설",
                            null,
                            new CharacterLoadout.TooltipLines(
                                "전설 아크 그리드 젬", List.of("가공 완료", "거래 불가"), List.of()))),
                    null,
                    new CharacterLoadout.TooltipLines(
                        "유물 아크 그리드 코어", List.of(), List.of("거래 불가", "분해불가")))),
            List.of());
    var loadout =
        new CharacterLoadout(
            List.of(weapon, earring), null, gems, cards, null, arkGrid, List.of(skill));
    return new FetchedCharacter(
        CharacterTestFixture.details(profile, loadout, List.of()), "{}", "[]");
  }

  private HttpResponse<String> get(String path, String name) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + path
                            + "?characterName="
                            + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
