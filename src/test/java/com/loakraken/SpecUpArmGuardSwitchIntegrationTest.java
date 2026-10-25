package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.KrakenScoreInputMapper;
import com.loakraken.character.infrastructure.ArmoryRawFixtures;
import com.loakraken.market.application.MarketAuctionService;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * 완갑 줄 스위치(spec-up.arm-guard.enabled, 기본 켬): 끄면 목록에 완갑 재련 줄이 없고 줄 상세도 SPEC_UP_CANDIDATE_NOT_FOUND다.
 * 켜진 경우는 SpecUpKrakenIntegrationTest(같은 원문 l01)가 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spec-up.enabled=true",
      "kraken-score.enabled=true",
      "spec-up.arm-guard.enabled=false"
    })
class SpecUpArmGuardSwitchIntegrationTest {
  private static final String SERVER = "완갑스위치서버";
  private static final String NAME = "완갑스위치";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MarketAuctionService auctions;

  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var keys = redis.keys("character:v*완갑스위치*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 스위치를_끄면_목록과_줄_상세에_완갑_줄이_없다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    search();
    var response =
        get("/api/v1/characters/spec-up?characterName=" + encode(NAME) + "&sort=KRAKEN&limit=200");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var candidates = mapper.readTree(response.body()).at("/data/candidates");
    assertThat(candidates.size()).isPositive();
    for (var candidate : candidates)
      assertThat(candidate.at("/change/kind").asText())
          .as(candidate.path("id").asText())
          .isNotEqualTo("ARM_GUARD");
    // 같은 원문에서 켜져 있으면 나오는 완갑 14 → 15 줄(SpecUpKrakenIntegrationTest)
    var detail =
        get(
            "/api/v1/characters/spec-up/candidates/EQUIPMENT_REFINE:16:15?characterName="
                + encode(NAME));
    assertThat(detail.statusCode()).as(detail.body()).isEqualTo(404);
    assertThat(mapper.readTree(detail.body()).path("code").asText())
        .isEqualTo("SPEC_UP_CANDIDATE_NOT_FOUND");
  }

  private void search() throws Exception {
    var raw =
        mapper
            .readTree(
                new ClassPathResource("kraken-score/l01.json")
                    .getContentAsString(StandardCharsets.UTF_8))
            .path("raw");
    var profile =
        new CharacterProfile(
            NAME,
            SERVER,
            "블레이드",
            70,
            new BigDecimal("1770.00"),
            new BigDecimal("4070.75"),
            null,
            null,
            null,
            null,
            List.of());
    var details = CharacterTestFixture.details(profile, ArmoryRawFixtures.loadout(raw), List.of());
    when(detailsPort.fetch(anyString()))
        .thenReturn(new FetchedCharacter(details, "{}", "[]", KrakenScoreInputMapper.from(raw)));
    assertThat(get("/api/v1/characters/search?characterName=" + encode(NAME)).statusCode())
        .isEqualTo(200);
  }

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
