package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.ArmoryPageFetchException;
import com.loakraken.character.application.ArmoryPagePort;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** 전투정보실 페이지 정보(#531): 검색한 캐릭터만, 값만 캐시, 처리정지는 실패와 같은 응답, 로그에 이름 없음. 실제 공식 요청 0. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "armory-page.enabled=true")
@ExtendWith(OutputCaptureExtension.class)
class ArmoryPageIntegrationTest {
  private static final String SERVER = "전투페서버";
  private static final String PATH = "/api/v1/characters/armory-page";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean ArmoryPagePort pages;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '전투페%'").update();
    var keys = new ArrayList<String>();
    for (var pattern : List.of("character:v*전투페*", "armory-page:v1:*")) {
      var found = redis.keys(pattern);
      if (found != null) keys.addAll(found);
    }
    if (!keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 검색하지_않은_캐릭터는_404이고_공식_페이지를_부르지_않는다() throws Exception {
    var response = get(PATH, "전투페없음");
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("CHARACTER_NOT_CACHED");
    verifyNoInteractions(pages, detailsPort);
  }

  @Test
  void 검색한_캐릭터는_생활_스킬을_주고_6시간_안에는_다시_부르지_않는다(CapturedOutput output) throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> fetched(invocation.getArgument(0)));
    when(pages.fetch("전투페가"))
        .thenReturn(
            new ArmoryPagePort.Page(
                List.of(
                    new ArmoryPagePort.LifeSkill("식물 채집", 14),
                    new ArmoryPagePort.LifeSkill("고고학", 70),
                    new ArmoryPagePort.LifeSkill("낚시", null))));
    assertThat(get("/api/v1/characters/search", "전투페가").statusCode()).isEqualTo(200);

    var first = get(PATH, "전투페가");
    assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
    ApiContractFixture.write("character-armory-page.json", first.body());
    var root = mapper.readTree(first.body());
    assertThat(root.path("code").asText()).isEqualTo("CHARACTER_ARMORY_PAGE_SUCCESS");
    var data = root.path("data");
    assertThat(data.path("characterName").asText()).isEqualTo("전투페가");
    assertThat(data.path("enabled").asBoolean()).isTrue();
    assertThat(data.path("source").asText()).isEqualTo("공식 전투정보실");
    assertThat(data.path("fetchedAt").isTextual()).isTrue();
    assertThat(data.at("/lifeSkills/0/name").asText()).isEqualTo("식물 채집");
    assertThat(data.at("/lifeSkills/1/level").asInt()).isEqualTo(70);
    assertThat(data.at("/lifeSkills/2/level").isNull()).isTrue();
    // 트라이포드·스킬 코드는 이 응답에 없다(PM 판정 10-17: 트라이포드는 Open API, 스킬 코드는 따로).
    assertThat(data.has("skills")).isFalse();
    assertThat(data.has("skillCode")).isFalse();

    var second = get(PATH, "전투페가");
    assertThat(mapper.readTree(second.body()).path("data")).isEqualTo(data);
    verify(pages, times(1)).fetch("전투페가");
    // 캐시에는 뽑은 값만(원문 없음), 로그에는 이름 없음.
    var cached = redis.opsForValue().get("armory-page:v1:전투페가");
    assertThat(cached).contains("고고학").doesNotContain("<", "profile-skill");
    assertThat(armoryLines(output)).doesNotContain("전투페가");
    SiteNames.assertAbsent(first.body());
  }

  @Test
  void 읽지_못하면_값이_null이고_10분_동안_다시_부르지_않는다(CapturedOutput output) throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> fetched(invocation.getArgument(0)));
    when(pages.fetch("전투페나"))
        .thenThrow(new ArmoryPageFetchException(ArmoryPageFetchException.Kind.NOT_FOUND, 404));
    get("/api/v1/characters/search", "전투페나");

    var first = get(PATH, "전투페나");
    assertThat(first.statusCode()).isEqualTo(200);
    var data = mapper.readTree(first.body()).path("data");
    assertThat(data.path("enabled").asBoolean()).isTrue();
    assertThat(data.path("source").isNull()).isTrue();
    assertThat(data.path("fetchedAt").isNull()).isTrue();
    assertThat(data.path("lifeSkills").isNull()).isTrue();
    get(PATH, "전투페나");
    verify(pages, times(1)).fetch("전투페나");
    assertThat(armoryLines(output))
        .contains("event=armory_page_fetch_failed")
        .doesNotContain("전투페나");
  }

  @Test
  void 처리정지_목록_캐릭터는_부르지_않고_읽기_실패와_글자까지_같은_응답이다() throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> fetched(invocation.getArgument(0)));
    when(pages.fetch("전투페실패"))
        .thenThrow(new ArmoryPageFetchException(ArmoryPageFetchException.Kind.NOT_FOUND, 404));
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('전투페정지', NULL, 'USER_REQUEST')")
        .update();
    get("/api/v1/characters/search", "전투페정지");
    get("/api/v1/characters/search", "전투페실패");

    var stopped = get(PATH, "전투페정지");
    var failed = get(PATH, "전투페실패");
    assertThat(stopped.statusCode()).isEqualTo(200).isEqualTo(failed.statusCode());
    // 이름만 다르고 나머지 글자는 같다(B5 — 목록에 있는지 드러내지 않음).
    assertThat(stopped.body().replace("전투페정지", "X")).isEqualTo(failed.body().replace("전투페실패", "X"));
    verify(pages, never()).fetch(eq("전투페정지"));
  }

  /** 이 기능이 쓴 로그 줄만(검색 경로 로그는 범위 밖). */
  private static String armoryLines(CapturedOutput output) {
    return output
        .getAll()
        .lines()
        .filter(line -> line.contains("armory_page") || line.contains("ArmoryPage"))
        .reduce("", (a, b) -> a + b + "\n");
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
    var loadout = new CharacterLoadout(List.of(), null, null, null, null, null, List.of());
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
