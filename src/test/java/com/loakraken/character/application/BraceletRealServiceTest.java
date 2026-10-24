package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.CharacterTestFixture;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.KrakenScoreEngine;
import com.loakraken.character.domain.KrakenScoreInputMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 팔찌 실 효율: 고정 자료 l01~l07(공식 원문에서 만든 입력)의 팔찌 %가 같은 시각 비교 기준 B 정보 탭의 팔찌 %(원본 함수 값)와 끝자리까지 같은지 본다(딜러
 * 5명, 서포터 2명 — 서포터는 반올림하지 않은 원본 값). 딜러는 화면 설정 '악마 피해'를 켠 값도 같이 본다(서포터는 그 설정이 없어 null).
 */
class BraceletRealServiceTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final BraceletRealService SERVICE = new BraceletRealService(tables());

  private static KrakenScoreEngine.Tables tables() {
    try {
      var doc = read("reference-tables");
      return new KrakenScoreEngine.Tables(doc.path("savedAt").asString(""), doc.path("tables"));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static JsonNode read(String id) throws IOException {
    return MAPPER.readTree(
        new ClassPathResource("kraken-score/" + id + ".json")
            .getContentAsString(StandardCharsets.UTF_8));
  }

  /** 고정 자료 원문의 직업·아크 패시브 효과 설명으로 캐릭터 결과를 만든다(역할 판정 근거). */
  private static CharacterResult result(JsonNode raw, JsonNode input) {
    var effects = new ArrayList<CharacterLoadout.PassiveEffect>();
    for (var e : raw.path("ArkPassive").path("Effects"))
      effects.add(
          new CharacterLoadout.PassiveEffect(
              e.path("Name").asString(""),
              e.path("Description").asString("").replaceAll("<[^>]*>", ""),
              null));
    var profile =
        new CharacterProfile(
            "표본",
            "서버",
            raw.path("ArmoryProfile").path("CharacterClassName").asString(""),
            70,
            new BigDecimal("1700"),
            null,
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
            null,
            new CharacterLoadout.ArkPassive("", true, List.of(), effects),
            null);
    return new CharacterResult(
        CharacterTestFixture.details(profile, loadout, List.of()), Instant.EPOCH, null, input);
  }

  @ParameterizedTest
  @CsvSource({
    "l01, DEALER, 11.3, 14.08",
    "l02, DEALER, 17.88, 17.88",
    "l03, DEALER, 16.6, 16.6",
    "l04, SUPPORT, 13.47857142857143,",
    "l05, DEALER, 13.4, 13.4",
    "l06, DEALER, 16.41, 19.32",
    "l07, SUPPORT, 18.107142857142858,"
  })
  void 팔찌_실_효율이_비교_기준과_끝자리까지_같다(String id, String role, String expected, String devil)
      throws IOException {
    var raw = read(id).path("raw");
    var real = SERVICE.compute(result(raw, KrakenScoreInputMapper.from(raw)));
    assertThat(real.status()).as(id).isEqualTo(BraceletRealEfficiency.Status.OK);
    assertThat(real.role()).as(id).isEqualTo(role);
    assertThat(real.percent()).as(id).isEqualTo(new BigDecimal(expected));
    assertThat(real.devilDamagePercent())
        .as(id + " 악마 피해")
        .isEqualTo(devil == null ? null : new BigDecimal(devil));
    assertThat(real.ruleVersion()).isEqualTo(BraceletRealEfficiency.RULE_VERSION);
  }

  @Test
  void 입력이_없거나_역할을_모르거나_팔찌가_없으면_UNSUPPORTED이고_percent는_null이다() throws IOException {
    var raw = read("l01").path("raw");
    var pending = SERVICE.compute(result(raw, null));
    assertThat(pending.status()).isEqualTo(BraceletRealEfficiency.Status.UNSUPPORTED);
    assertThat(pending.reasonCode()).isEqualTo("INPUT_PENDING");
    assertThat(pending.role()).isNull();
    assertThat(pending.percent()).isNull();
    // 서포터 가능 직업인데 깨달음 노드가 없으면 판정하지 못한다.
    var bard =
        new CharacterResult(
            CharacterTestFixture.details(
                new CharacterProfile(
                    "표본",
                    "서버",
                    "바드",
                    70,
                    new BigDecimal("1700"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of()),
                new CharacterLoadout(List.of(), null, null, null, null, null),
                List.of()),
            Instant.EPOCH,
            null,
            KrakenScoreInputMapper.from(raw));
    assertThat(SERVICE.compute(bard).reasonCode()).isEqualTo("ROLE_UNKNOWN");
    // 팔찌가 없으면 원본 총배율이 1이라 2.1%가 나오지만 그건 팔찌 효율이 아니다 — 내지 않는다(딜러·서포터 모두).
    for (var id : List.of("l01", "l04")) {
      var doc = read(id).path("raw");
      var input = KrakenScoreInputMapper.from(doc);
      ((ObjectNode) input.path("armory").path("accessory")).putNull("bangle");
      var none = SERVICE.compute(result(doc, input));
      assertThat(none.reasonCode()).as(id).isEqualTo("UNAVAILABLE");
      assertThat(none.role()).as(id).isNotNull();
      assertThat(none.percent()).as(id).isNull();
      assertThat(none.devilDamagePercent()).as(id).isNull();
    }
    // 무기·아크 패시브가 없는 빈 입력도 계산하지 않는다.
    var empty = KrakenScoreInputMapper.from(MAPPER.readTree("{}"));
    assertThat(SERVICE.compute(result(raw, empty)).reasonCode()).isEqualTo("UNAVAILABLE");
  }

  @Test
  void 계산식이_알아보지_못하는_팔찌_줄이_있으면_내지_않고_체력_줄은_그대로다() throws IOException {
    // 코드 리뷰 10-25: 알아보지 못한 줄은 배율 1로 지나가 효율이 낮게 나왔다. 실제 원문 33명에서 그런 줄은 체력 +N뿐(효과 없음).
    var raw = read("l01").path("raw");
    var base = SERVICE.compute(result(raw, KrakenScoreInputMapper.from(raw)));
    var withVitality = KrakenScoreInputMapper.from(raw);
    ((tools.jackson.databind.node.ArrayNode)
            withVitality.path("armory").path("accessory").path("bangle").path("option"))
        .add("체력+3500");
    var vitality = SERVICE.compute(result(raw, withVitality));
    assertThat(vitality.status()).isEqualTo(BraceletRealEfficiency.Status.OK);
    assertThat(vitality.percent()).isEqualByComparingTo(base.percent());
    // 빈 칸(효과 부여 가능)과 회복만 하는 [긴급수혈]도 효과 없는 줄(계산 검증 도달 범위 10-25: 저장된 요약 746명 중 8명)
    for (var line :
        List.of("효과부여가능", "효과 부여 가능", "[긴급수혈]생명력이30%이하로떨어지면즉시생명력을13000회복한다.(재사용대기시간180초)")) {
      var withLine = KrakenScoreInputMapper.from(raw);
      ((tools.jackson.databind.node.ArrayNode)
              withLine.path("armory").path("accessory").path("bangle").path("option"))
          .add(line);
      var zero = SERVICE.compute(result(raw, withLine));
      assertThat(zero.status()).as(line).isEqualTo(BraceletRealEfficiency.Status.OK);
      assertThat(zero.percent()).as(line).isEqualByComparingTo(base.percent());
    }
    // 옵션 표에 없고 추가 공격 표에만 있는 줄([반격])은 계산식이 쓰므로 계산하고 값이 오른다
    var withAddon = KrakenScoreInputMapper.from(raw);
    ((tools.jackson.databind.node.ArrayNode)
            withAddon.path("armory").path("accessory").path("bangle").path("option"))
        .add("[반격]기상기사용후8초동안무기공격력이2200증가한다.");
    var addon = SERVICE.compute(result(raw, withAddon));
    assertThat(addon.status()).isEqualTo(BraceletRealEfficiency.Status.OK);
    assertThat(addon.percent()).isGreaterThan(base.percent());
    // 빈 칸 문구가 다른 문장 안에 있으면 효과 없는 줄로 보지 않는다
    var embedded = KrakenScoreInputMapper.from(raw);
    ((tools.jackson.databind.node.ArrayNode)
            embedded.path("armory").path("accessory").path("bangle").path("option"))
        .add("효과부여가능한공격력이3%증가한다.");
    assertThat(SERVICE.compute(result(raw, embedded)).reasonCode()).isEqualTo("UNAVAILABLE");
    var withUnknown = KrakenScoreInputMapper.from(raw);
    ((tools.jackson.databind.node.ArrayNode)
            withUnknown.path("armory").path("accessory").path("bangle").path("option"))
        .add("알 수 없는 새 효과가 3% 증가한다.");
    var unknown = SERVICE.compute(result(raw, withUnknown));
    assertThat(unknown.reasonCode()).isEqualTo("UNAVAILABLE");
    assertThat(unknown.percent()).isNull();
  }
}
