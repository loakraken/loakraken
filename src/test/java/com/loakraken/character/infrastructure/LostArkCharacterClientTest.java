package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loakraken.ApiContractFixture;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.CharacterResult;
import com.loakraken.character.domain.ObservationState;
import com.loakraken.character.presentation.CharacterResponse;
import com.loakraken.common.lostark.LostArkConfiguration;
import com.loakraken.common.lostark.LostArkMetrics;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class LostArkCharacterClientTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final LostArkMetrics metrics = new LostArkMetrics(registry);
  private HttpServer server;
  private ExecutorService executor;
  private final AtomicInteger requests = new AtomicInteger();
  private volatile int status = 200;
  private volatile String body =
      """
      {"ArmoryProfile":{"CharacterName":"모험가","ServerName":"루페온","CharacterClassName":"바드",
       "CharacterLevel":70,"ItemAvgLevel":"1,700.50","CombatPower":"12,345.67",
       "Stats":[{"Type":"치명","Value":"100","Tooltip":[]}],"FutureField":"ignored"}}
      """;
  private volatile String authorization;
  private volatile String requestPath;
  private volatile long delayMillis;
  private volatile String siblingsBody = "[]";
  private volatile int siblingsStatus = 200;
  private volatile String armoryQuery;

  @BeforeEach
  void 설정() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          authorization = exchange.getRequestHeaders().getFirst("Authorization");
          requestPath = exchange.getRequestURI().getRawPath();
          try {
            if (delayMillis > 0) Thread.sleep(delayMillis);
            boolean siblings = requestPath.endsWith("/siblings");
            if (!siblings) armoryQuery = exchange.getRequestURI().getRawQuery();
            byte[] bytes = (siblings ? siblingsBody : body).getBytes(StandardCharsets.UTF_8);
            int responseStatus = siblings ? siblingsStatus : status;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status == 429) exchange.getResponseHeaders().set("X-RateLimit-Remaining", "0");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void 정리() {
    for (var meter : registry.getMeters()) {
      var id = meter.getId();
      assertThat(id.toString())
          .doesNotContain("모험가", "원정대캐릭터", "test-token", "secret", "http", "/characters/");
      assertThat(id.getTags())
          .allMatch(tag -> java.util.Set.of("endpoint", "outcome", "event").contains(tag.getKey()));
    }
    server.stop(0);
    executor.close();
  }

  @Test
  void 인증_헤더와_한글_경로를_전송하고_외부_숫자를_변환한다() {
    var result = client("test-token", Duration.ofSeconds(2)).fetch("모험가");
    assertThat(authorization).isEqualTo("Bearer test-token");
    assertThat(requestPath).contains("%EB%AA%A8%ED%97%98%EA%B0%80");
    assertThat(armoryQuery)
        .contains(
            "profiles%2Bequipment%2Bengravings%2Bgems%2Bcards%2Barkpassive%2Barkgrid%2Bcombat-skills");
    assertThat(result.details().profile().itemLevel()).isEqualByComparingTo("1700.50");
    assertThat(result.details().profile().combatPower()).isEqualByComparingTo("12345.67");
    assertThat(result.details().profile().stats()).hasSize(1);
    assertThat(result.details().loadout().equipment()).isEmpty();
    assertThat(result.details().siblings()).isEmpty();
    assertThat(requests).hasValue(2);
    assertThat(
            registry
                .get("loakraken.lostark.requests")
                .tags("endpoint", "armory", "outcome", "success")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("loakraken.lostark.requests")
                .tags("endpoint", "siblings", "outcome", "success")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void 종합_전투정보와_원정대를_매핑하고_Tooltip을_제외한다() throws Exception {
    try (var resource = getClass().getResourceAsStream("/fixtures/armory-summary.json")) {
      body = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
    siblingsBody =
        """
      [{"CharacterName":"원정대캐릭터","ServerName":"카단","CharacterClassName":"바드","CharacterLevel":70,"ItemAvgLevel":"1,650.00"}]
      """;
    var fetched = client("test", Duration.ofSeconds(2)).fetch("모험가");
    var details = fetched.details();
    assertThat(fetched.armoryJson()).contains("unparsed-secret-tooltip", "FutureField");
    var loadout = details.loadout();
    assertThat(loadout.equipment().getFirst().name()).isEqualTo("테스트 무기");
    assertThat(loadout.equipment().getFirst().detail()).isNull();
    assertThat(details.profile().skillPoints())
        .isEqualTo(new com.loakraken.character.domain.CharacterProfile.SkillPoints(482, 483));
    assertThat(details.coverage().skills()).isEqualTo(ObservationState.PROVIDED);
    var skill = loadout.skills().getFirst();
    assertThat(skill.partBreakLevel()).isEqualTo(1);
    assertThat(skill.counter()).isTrue();
    assertThat(skill.tripods()).extracting("name").containsExactly("약점 노출");
    assertThat(skill.rune().grade()).isEqualTo("전설");
    assertThat(skill.gemEffects().getFirst().kind())
        .isEqualTo(com.loakraken.character.domain.CharacterLoadout.GemEffectKind.DAMAGE);
    assertThat(loadout.skills().get(1).counter()).isNull();
    assertThat(loadout.skills().get(1).rune()).isNull();
    var helmet = loadout.equipment().get(1).detail();
    assertThat(helmet.refineLevel()).isEqualTo(19);
    assertThat(helmet.quality()).isEqualTo(95);
    assertThat(helmet.itemLevel()).isEqualTo(1770);
    assertThat(helmet.arkPassivePoint().value()).isEqualTo(24);
    assertThat(loadout.engravings().arkPassiveEffects().getFirst().abilityStoneLevel()).isNull();
    assertThat(loadout.gems().items().getFirst().level()).isEqualTo(9);
    assertThat(loadout.gems().effects().skills().getFirst().description())
        .containsExactly("효과1", "효과2");
    assertThat(loadout.cards().items().getFirst().awakeCount()).isEqualTo(5);
    assertThat(loadout.cards().effects().getFirst().items().getFirst().name()).isEqualTo("세트명");
    assertThat(loadout.arkPassive().enabled()).isTrue();
    assertThat(loadout.arkPassive().points().getFirst().value()).isEqualTo(120);
    assertThat(loadout.arkGrid().slots().getFirst().gems().getFirst().active()).isFalse();
    assertThat(loadout.arkGrid().effects().getFirst().level()).isEqualTo(2);
    assertThat(details.siblings().getFirst().itemLevel()).isEqualByComparingTo("1650");
    var result = new CharacterResult(details, Instant.parse("2026-10-04T00:00:00Z"));
    var mapper = new JsonMapper();
    var cacheJson = mapper.writeValueAsString(result);
    assertThat(mapper.readValue(cacheJson, CharacterResult.class)).isEqualTo(result);
    var response = mapper.writeValueAsString(CharacterResponse.from(result));
    ApiContractFixture.write("character-full.json", response);
    assertThat(response)
        .contains("테스트 무기", "원정대캐릭터", "\"parserVersion\":\"equipment-tooltip-v3\"")
        .doesNotContain(
            "Tooltip", "ToolTip", "unparsed-secret-tooltip", "FutureField", "Element_00", "<FONT");
    assertThat(requests).hasValue(2);
  }

  @Test
  void 원천_마크업은_일반_텍스트로_매핑하고_원본_JSON에는_보존한다() throws Exception {
    body =
        """
        {"ArmoryProfile":{"CharacterName":"모험가","ServerName":"루페온","CharacterClassName":"바드",
          "CharacterLevel":70,"ItemAvgLevel":"1,700.50",
          "Title":"<img src='emoticon_tooltip_ranking_1' width='18' height='18' vspace='-4'></img>칭호",
          "GuildName":"길드&amp;이름"},
         "ArmoryEngraving":{"ArkPassiveEffects":[{"Grade":"유물","Level":4,"Name":"원한",
           "Description":"피해가 <FONT COLOR='#99ff99'>27.00%</FONT> 증가<BR>받는 피해 증가"}]},
         "ArmoryGem":{"Gems":[{"Slot":0,"Level":6,"Grade":"전설",
           "Name":"<P ALIGN='CENTER'><FONT COLOR='#F99200'>6레벨 광휘의 보석 (귀속)</FONT></P>"}],
          "Effects":{"Description":"<FONT COLOR='#B7FB00'>기본 공격력 총합 : 4.95%</FONT>",
           "Skills":[{"GemSlot":0,"Name":"스킬","Description":["<FONT COLOR='#FFD200'>피해</FONT> 증가"],
            "Option":"공격력 &lt; 1% 증가"}]}},
         "ArkPassive":{"IsArkPassive":true,"Effects":[{"Name":"깨달음",
           "Description":"<FONT color='#83E9FF'>깨달음</FONT> 1티어 <FONT color='#83E9FF'>버스트 강화 Lv.1</FONT>"}]}}
        """;
    var fetched = client("test", Duration.ofSeconds(2)).fetch("모험가");
    var profile = fetched.details().profile();
    var loadout = fetched.details().loadout();
    assertThat(profile.title()).isEqualTo("칭호");
    assertThat(profile.guildName()).isEqualTo("길드&이름");
    assertThat(loadout.engravings().arkPassiveEffects().getFirst().description())
        .isEqualTo("피해가 27.00% 증가\n받는 피해 증가");
    assertThat(loadout.gems().items().getFirst().name()).isEqualTo("6레벨 광휘의 보석 (귀속)");
    assertThat(loadout.gems().effects().description()).isEqualTo("기본 공격력 총합 : 4.95%");
    assertThat(loadout.gems().effects().skills().getFirst().description()).containsExactly("피해 증가");
    assertThat(loadout.gems().effects().skills().getFirst().option()).isEqualTo("공격력 < 1% 증가");
    assertThat(loadout.arkPassive().effects().getFirst().description())
        .isEqualTo("깨달음 1티어 버스트 강화 Lv.1");
    assertThat(fetched.armoryJson()).contains("<FONT COLOR='#F99200'>", "<BR>");
    var result = new CharacterResult(fetched.details(), Instant.parse("2026-10-04T00:00:00Z"));
    var mapper = new JsonMapper();
    var cacheJson = mapper.writeValueAsString(result);
    var response = mapper.writeValueAsString(CharacterResponse.from(result));
    assertThat(cacheJson).doesNotContainIgnoringCase("<font", "<p ", "<br", "<img");
    assertThat(response).doesNotContainIgnoringCase("<font", "<p ", "<br", "<img");
  }

  @Test
  void 원정대_조회_실패는_불완전한_성공_결과를_만들지_않는다() {
    siblingsStatus = 503;
    assertError(client("test", Duration.ofSeconds(2)), CharacterErrorCode.LOST_ARK_API_ERROR);
    assertThat(requests).hasValue(2);
    assertThat(
            registry
                .get("loakraken.lostark.requests")
                .tags("endpoint", "siblings", "outcome", "error")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({
    "section-null,NOT_PROVIDED,NOT_PROVIDED",
    "items-null,PROVIDED,NOT_PROVIDED",
    "items-empty,PROVIDED,PROVIDED_EMPTY",
    "items-provided,PROVIDED,PROVIDED"
  })
  void 원천_보석_상태는_정규화와_캐시_직렬화_후에도_구별된다(
      String fixture, ObservationState section, ObservationState items) throws Exception {
    try (var resource =
        getClass().getResourceAsStream("/fixtures/observation-gems-" + fixture + ".json")) {
      body = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
    var fetched = client("test", Duration.ofSeconds(2)).fetch("모험가");
    var result = new CharacterResult(fetched.details(), Instant.parse("2026-10-04T00:00:00Z"));
    var mapper = new JsonMapper();
    var restored = mapper.readValue(mapper.writeValueAsString(result), CharacterResult.class);
    assertThat(restored).isEqualTo(result);
    assertThat(restored.details().coverage().gems().state()).isEqualTo(section);
    assertThat(restored.details().coverage().gems().collections().get("items")).isEqualTo(items);
    assertThat(restored.details().coverage().siblings()).isEqualTo(ObservationState.PROVIDED_EMPTY);
    var response = mapper.writeValueAsString(CharacterResponse.from(restored));
    var json = mapper.readTree(response);
    assertThat(json.at("/data").isMissingNode()).isTrue();
    assertThat(json.at("/coverage/gems/state").asText()).isEqualTo(section.name());
    assertThat(json.at("/coverage/gems/collections/items").asText()).isEqualTo(items.name());
    var expectedCode =
        switch (fixture) {
          case "section-null" -> "GEMS_UNAVAILABLE";
          case "items-null" -> "GEM_ITEMS_UNAVAILABLE";
          case "items-empty" -> "GEMS_NOT_OBSERVED";
          default -> "";
        };
    assertThat(json.at("/analysis/diagnostics/ruleVersion").asText()).isEqualTo("observed-v3");
    boolean found = false;
    for (var finding : json.at("/analysis/diagnostics/findings")) {
      var path = finding.path("evidencePath").asText();
      assertThat(json.at(path.substring("/data".length())).isMissingNode()).isFalse();
      if (finding.path("code").asText().equals(expectedCode)) {
        found = true;
        if (fixture.equals("items-null")) {
          assertThat(path).isEqualTo("/data/coverage/gems/collections/items");
          assertThat(finding.path("observedCount").isNull()).isTrue();
        }
      }
    }
    assertThat(found).isEqualTo(!expectedCode.isEmpty());
    ApiContractFixture.write(
        "observation-gems-" + fixture + ".json",
        "{\"code\":\"CHARACTER_SEARCH_SUCCESS\",\"message\":\"합성 테스트\",\"data\":" + response + "}");
  }

  @Test
  void null_항목만_있는_목록은_원천의_빈_목록으로_바꾸지_않는다() {
    body = body.substring(0, body.lastIndexOf('}')) + ",\"ArmoryEquipment\":[null]}";
    var details = client("test", Duration.ofSeconds(2)).fetch("모험가").details();
    assertThat(details.loadout().equipment()).isEmpty();
    assertThat(details.coverage().equipment()).isEqualTo(ObservationState.PROVIDED);
    assertThat(details.coverage().engravings().collections())
        .containsEntry("slots", ObservationState.NOT_PROVIDED);
  }

  @Test
  void 전투정보만_조회하면_원정대를_요청하지_않고_원정대_상태를_null로_둔다() {
    var fetched = client("test", Duration.ofSeconds(2)).fetchArmory("모험가");
    assertThat(requests).hasValue(1);
    assertThat(requestPath).doesNotEndWith("/siblings");
    assertThat(fetched.details().siblings()).isNull();
    assertThat(fetched.details().coverage().siblings()).isNull();
    assertThat(fetched.siblingsJson()).isNull();
    assertThat(fetched.details().profile().itemLevel()).isEqualByComparingTo("1700.50");
  }

  @Test
  void 원정대만_조회하면_전투정보를_요청하지_않는다() {
    siblingsBody =
        """
        [{"CharacterName":"모험가","ServerName":"루페온","CharacterClassName":"바드","CharacterLevel":70,"ItemAvgLevel":"1,700.50"},
         null,
         {"CharacterName":"원정대캐릭터","ServerName":"카단","CharacterClassName":"바드","CharacterLevel":60,"ItemAvgLevel":"1,650.00"}]
        """;
    var siblings = client("test", Duration.ofSeconds(2)).fetchSiblings("모험가");
    assertThat(requests).hasValue(1);
    assertThat(requestPath).endsWith("/siblings");
    assertThat(siblings).extracting("characterName").containsExactly("모험가", "원정대캐릭터");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "200|[]|CHARACTER_NOT_FOUND",
        "404|[]|CHARACTER_NOT_FOUND",
        "200|null|LOST_ARK_API_ERROR"
      })
  void 원정대_단독_조회의_빈_목록과_404는_미존재다(int responseStatus, String responseBody, String code) {
    siblingsStatus = responseStatus;
    siblingsBody = responseBody;
    assertThatThrownBy(() -> client("test", Duration.ofSeconds(2)).fetchSiblings("없는캐릭터"))
        .isInstanceOfSatisfying(
            CharacterException.class,
            exception ->
                assertThat(exception.errorCode()).isEqualTo(CharacterErrorCode.valueOf(code)));
  }

  @Test
  void 원정대_null_응답은_불완전한_외부_응답이다() {
    siblingsBody = "null";
    assertError(client("test", Duration.ofSeconds(2)), CharacterErrorCode.LOST_ARK_API_ERROR);
  }

  @Test
  void 잘못된_원정대_숫자는_외부_응답_오류다() {
    siblingsBody =
        """
      [{"CharacterName":"캐릭터","ServerName":"카단","CharacterClassName":"바드","ItemAvgLevel":"invalid"}]
      """;
    assertError(client("test", Duration.ofSeconds(2)), CharacterErrorCode.LOST_ARK_API_ERROR);
  }

  @Test
  void 빈_프로필은_미존재로_처리한다() {
    body = "null";
    assertError(client("test", Duration.ofSeconds(2)), CharacterErrorCode.CHARACTER_NOT_FOUND);
  }

  @Test
  void 필수_필드_누락은_외부_응답_오류다() {
    body = "{}";
    assertError(client("test", Duration.ofSeconds(2)), CharacterErrorCode.LOST_ARK_API_ERROR);
  }

  @Test
  void 잘못된_JSON은_외부_응답_오류다() {
    body = "secret-invalid-json";
    assertError(client("test", Duration.ofSeconds(2)), CharacterErrorCode.LOST_ARK_API_ERROR);
  }

  @Test
  void 키가_없으면_외부_요청을_보내지_않는다() {
    assertError(client("", Duration.ofSeconds(2)), CharacterErrorCode.LOST_ARK_NOT_CONFIGURED);
    assertThat(requests).hasValue(0);
    assertThat(registry.getMeters()).isEmpty();
  }

  @Test
  void 호출_제한은_재시도하지_않고_후속_요청도_차단한다() {
    status = 429;
    var client = client("test", Duration.ofSeconds(2));
    assertError(client, CharacterErrorCode.LOST_ARK_RATE_LIMITED);
    assertError(client, CharacterErrorCode.LOST_ARK_RATE_LIMITED);
    assertThat(requests).hasValue(1);
    assertThat(
            registry
                .get("loakraken.lostark.requests")
                .tag("outcome", "rate_limited")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            registry.get("loakraken.lostark.gate").tag("event", "upstream_429").counter().count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("loakraken.lostark.gate")
                .tag("event", "upstream_backoff_reject")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void 응답_지연은_타임아웃으로_처리한다() {
    delayMillis = 500;
    assertError(client("test", Duration.ofMillis(100)), CharacterErrorCode.LOST_ARK_API_TIMEOUT);
    var timer = registry.get("loakraken.lostark.requests").tag("outcome", "timeout").timer();
    assertThat(timer.count()).isEqualTo(1);
    assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isPositive();
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 500, 502, 503})
  void 외부_오류를_그대로_노출하거나_재시도하지_않는다(int responseStatus) {
    status = responseStatus;
    body = "secret-upstream-error";
    assertError(client("test", Duration.ofSeconds(2)), CharacterErrorCode.LOST_ARK_API_ERROR);
    assertThat(requests).hasValue(1);
    assertThat(registry.get("loakraken.lostark.requests").tag("outcome", "error").timer().count())
        .isEqualTo(1);
  }

  @Test
  void 미존재_응답_뒤에도_다음_요청을_처리할_수_있다() {
    var client = client("test", Duration.ofSeconds(2));
    status = 404;
    assertError(client, CharacterErrorCode.CHARACTER_NOT_FOUND);
    assertThat(
            registry.get("loakraken.lostark.requests").tag("outcome", "not_found").timer().count())
        .isEqualTo(1);
    status = 200;
    assertThat(client.fetch("모험가").details().profile().characterName()).isEqualTo("모험가");
  }

  private LostArkCharacterClient client(String key, Duration timeout) {
    var properties =
        new LostArkProperties(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            key,
            Duration.ofSeconds(2),
            timeout,
            90,
            1,
            Duration.ofMinutes(5));
    return new LostArkCharacterClient(
        new LostArkConfiguration().lostArkRestClient(properties),
        properties,
        new LostArkRequestGate(Clock.systemUTC(), properties, metrics),
        new JsonMapper(),
        metrics);
  }

  private void assertError(LostArkCharacterClient client, CharacterErrorCode code) {
    assertThatThrownBy(() -> client.fetch("모험가"))
        .isInstanceOfSatisfying(
            CharacterException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(code));
  }
}
