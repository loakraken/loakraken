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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 아크 그리드 젬 실 효율(#520): 고정 자료 l01~l07(공식 원문에서 만든 입력)의 실 효율이 같은 시각 비교 기준 B의 젬 실 효율(전체·질서·혼돈, 줄별)과
 * 끝자리까지 같은지 본다(딜러 5명, 서포터 2명).
 */
class ArkGridRealServiceTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final ArkGridRealService SERVICE = new ArkGridRealService(tables());

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
        new ClassPathResource("kraken-score/" + id + ".json").getContentAsString(StandardCharsets.UTF_8));
  }

  /** 고정 자료 원문의 직업·아크 패시브 효과 설명으로 캐릭터 정보를 만든다(역할 판정 근거). */
  private static com.loakraken.character.domain.CharacterDetails details(JsonNode raw) {
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
            List.of(), null, null, null, new CharacterLoadout.ArkPassive("", true, List.of(), effects), null);
    return CharacterTestFixture.details(profile, loadout, List.of());
  }

  private static void assertPercent(String what, JsonNode expected, BigDecimal actual) {
    if (expected == null || expected.isNull() || expected.isMissingNode())
      assertThat(actual).as(what).isNull();
    else assertThat(actual).as(what).isNotNull().isEqualByComparingTo(BigDecimal.valueOf(expected.asDouble()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"l01", "l02", "l03", "l04", "l05", "l06", "l07"})
  void 젬_실_효율이_비교_기준과_끝자리까지_같다(String id) throws IOException {
    var doc = read(id);
    var raw = doc.path("raw");
    var real = SERVICE.compute(details(raw), KrakenScoreInputMapper.from(raw));
    var expected = doc.path("expectedGemEfficiency");
    assertThat(real.status()).as(id).isEqualTo(ArkGridRealEfficiency.Status.OK);
    var total = expected.path("전체");
    assertPercent(id + " 전체", total.path("efficiency"), real.percent());
    assertPercent(id + " 옵션", total.path("optionEfficiency"), real.optionsPercent());
    assertPercent(id + " 포인트", total.path("pointEfficiency"), real.pointsPercent());
    assertThat(real.options()).hasSize(total.path("lines").size());
    for (int i = 0; i < real.options().size(); i++) {
      var want = total.path("lines").get(i);
      var got = real.options().get(i);
      assertThat(got.name()).isEqualTo(want.path("name").asString(""));
      assertThat(got.level()).isEqualTo(want.path("level").asInt());
      assertPercent(id + " " + got.name(), want.path("efficiency"), got.percent());
    }
    for (var series : real.series()) {
      var want = expected.path(series.series());
      assertPercent(id + " " + series.series(), want.path("efficiency"), series.percent());
      assertPercent(id + " " + series.series() + " 옵션", want.path("optionEfficiency"), series.optionsPercent());
      assertPercent(id + " " + series.series() + " 포인트", want.path("pointEfficiency"), series.pointsPercent());
      assertThat(series.options()).hasSize(want.path("lines").size());
      for (int i = 0; i < series.options().size(); i++)
        assertPercent(
            id + " " + series.series() + " " + series.options().get(i).name(),
            want.path("lines").get(i).path("efficiency"),
            series.options().get(i).percent());
    }
    // 비교 기준에 계열 값이 있으면 우리도 그 계열이 있다.
    for (var name : List.of("질서", "혼돈"))
      if (expected.path(name).isObject())
        assertThat(real.series()).extracting(ArkGridRealEfficiency.Series::series).contains(name);
  }

  @Test
  void 입력이_없거나_역할을_모르면_UNSUPPORTED이고_수치는_비고_목록은_빈_배열이다() throws IOException {
    var raw = read("l01").path("raw");
    var pending = SERVICE.compute(details(raw), null);
    assertThat(pending.status()).isEqualTo(ArkGridRealEfficiency.Status.UNSUPPORTED);
    assertThat(pending.reasonCode()).isEqualTo("INPUT_PENDING");
    assertThat(pending.percent()).isNull();
    assertThat(pending.options()).isEmpty();
    assertThat(pending.series()).isEmpty();
    // 서포터 가능 직업인데 깨달음 노드가 없으면 판정하지 못한다.
    var bard =
        CharacterTestFixture.details(
            new CharacterProfile(
                "표본", "서버", "바드", 70, new BigDecimal("1700"), null, null, null, null, null, List.of()),
            new CharacterLoadout(List.of(), null, null, null, null, null),
            List.of());
    assertThat(SERVICE.compute(bard, KrakenScoreInputMapper.from(raw)).reasonCode())
        .isEqualTo("ROLE_UNKNOWN");
    // 아크 그리드 코어가 없으면 계산하지 않는다.
    var empty = KrakenScoreInputMapper.from(MAPPER.readTree("{}"));
    assertThat(SERVICE.compute(details(raw), empty).reasonCode()).isEqualTo("UNAVAILABLE");
  }
}
