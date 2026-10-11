package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.CharacterTestFixture;
import com.loakraken.character.application.CombatPowerSimulationService.Status;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.CombatPowerArkGridModel;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ArkGridEfficiencyServiceTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private final ArkGridEfficiencyService service = new ArkGridEfficiencyService(coefficients());

  private static CombatPowerArkGridModel.Coefficients coefficients() {
    try (InputStream in =
        new ClassPathResource("combat-power/ark-grid-coefficients.json").getInputStream()) {
      return MAPPER.readValue(in, CombatPowerArkGridModel.Coefficients.class);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static JsonNode sample(String name) throws Exception {
    try (InputStream in =
        ArkGridEfficiencyServiceTest.class.getResourceAsStream(
            "/verification/combat-power/" + name)) {
      return MAPPER.readTree(in);
    }
  }

  private static CharacterResult result(
      String className, BigDecimal combatPower, CharacterLoadout.ArkGrid grid) {
    var profile =
        new CharacterProfile(
            "표본",
            "루페온",
            className,
            70,
            new BigDecimal("1700"),
            combatPower,
            300,
            null,
            null,
            null,
            List.of(),
            null);
    return new CharacterResult(
        CharacterTestFixture.details(
            profile, new CharacterLoadout(List.of(), null, null, null, null, grid), List.of()),
        Instant.EPOCH);
  }

  private static CharacterResult dealer(CharacterLoadout.ArkGrid grid) {
    return result("블레이드", new BigDecimal("3345.24"), grid);
  }

  /** 표본 코어 이름 "질서의 해(직업)"·"혼돈의 달 : 불타는 일격" → 공식 이름 "질서의 해 코어 : …". */
  private static String officialName(String sampleName) {
    if (sampleName.endsWith("(직업)")) return sampleName.replace("(직업)", "") + " 코어 : 직업 스킬";
    return sampleName.replace(" : ", " 코어 : ");
  }

  @Test
  void 계산_검증_표본_5명의_코어_효율과_합계를_재현한다() throws Exception {
    var cores = sample("ark-grid-core-efficiency-2026-10-12.json").path("samples");
    var gems = sample("ark-grid-gem-efficiency-2026-10-12.json").path("samples");
    assertThat(cores.size()).isEqualTo(5);
    int coreLines = 0;
    for (var entry : cores.properties()) {
      var name = entry.getKey();
      var expected = entry.getValue();
      var slots = new ArrayList<CharacterLoadout.GridSlot>();
      int index = 0;
      for (var core : expected.path("cores"))
        slots.add(
            new CharacterLoadout.GridSlot(
                index++,
                null,
                officialName(core.path("core").asText()),
                core.path("point").asInt(),
                "Ancient".equals(core.path("grade").asText()) ? "고대" : "유물",
                List.of()));
      var effects = new ArrayList<CharacterLoadout.GridEffect>();
      for (var option : gems.path(name).path("options").properties())
        effects.add(
            new CharacterLoadout.GridEffect(
                option.getKey(), option.getValue().path("level").asInt()));
      // 딜러에게 0인 서포터 옵션은 배수에 넣지 않는다.
      effects.add(new CharacterLoadout.GridEffect("낙인력", 32));
      var value = service.efficiency(dealer(new CharacterLoadout.ArkGrid(slots, effects)));

      assertThat(value.status()).as(name).isEqualTo(Status.OK);
      assertThat(value.reasonCode()).isNull();
      // c03 표본 37.25는 젬 합계를 4.69%로 반올림한 뒤 곱한 값이다(1.31106 × 1.0469 = 37.2549%). 정의(반올림 전 곱)대로면
      // 1.31106 × 1.046903 = 37.2553% → 37.26이다. 나머지 4명은 두 방식이 같다.
      var total =
          "c03".equals(name)
              ? new BigDecimal("37.26")
              : expected.path("arkGridTotalPct").decimalValue();
      assertThat(value.percent()).as(name + " 아크 그리드").isEqualByComparingTo(total);
      assertThat(value.coresPercent())
          .as(name + " 코어 합")
          .isEqualByComparingTo(
              expected.path("coresTotalPct").decimalValue().setScale(2, RoundingMode.HALF_UP));
      assertThat(value.lines()).hasSize(7);
      for (int i = 0; i < 6; i++) {
        var line = value.lines().get(i);
        assertThat(line.slotIndex()).isEqualTo(i);
        assertThat(line.percent())
            .as(name + " " + line.text())
            .isEqualByComparingTo(
                expected.path("cores").get(i).path("efficiencyPct").decimalValue());
        coreLines++;
      }
      var gemLine = value.lines().get(6);
      assertThat(gemLine.slotIndex()).isNull();
      assertThat(gemLine.text()).startsWith("젬 옵션(공격력 Lv.");
      assertThat(gemLine.percent())
          .as(name + " 젬")
          .isEqualByComparingTo(expected.path("gemsTotalPct").decimalValue());
    }
    assertThat(coreLines).isEqualTo(30);
  }

  @Test
  void 아크_그리드가_없으면_null이다() {
    assertThat(service.efficiency(dealer(null))).isNull();
    assertThat(service.efficiency(dealer(new CharacterLoadout.ArkGrid(List.of(), List.of()))))
        .isNull();
  }

  @Test
  void 전설은_14P_영웅은_10P까지만_고대는_17P부터_1퍼센트포인트를_더한다() {
    var grid =
        new CharacterLoadout.ArkGrid(
            List.of(
                slot(0, "질서의 해 코어 : 직업 스킬", 20, "전설"),
                slot(1, "질서의 달 코어 : 직업 스킬", 17, "영웅"),
                slot(2, "질서의 별 코어 : 직업 스킬", 16, "고대"),
                slot(3, "혼돈의 해 코어 : 재빠른 공격", 20, "고대"),
                slot(4, "혼돈의 달 코어 : 부수는 일격", 9, "유물")),
            List.of());
    var value = service.efficiency(dealer(grid));
    assertThat(value.lines())
        .extracting(ArkGridEfficiencyService.ArkGridLineEfficiency::percent)
        .containsExactly(
            new BigDecimal("4.00"),
            new BigDecimal("1.50"),
            new BigDecimal("2.50"),
            new BigDecimal("3.00"),
            new BigDecimal("0.00"),
            new BigDecimal("0.00"));
    assertThat(value.status()).isEqualTo(Status.OK);
  }

  @Test
  void 읽지_못한_코어와_무기_코어는_그_줄과_전체를_계산하지_않는다() {
    // 회귀 점검표 B8: 포인트·등급이 null이어도 예외 없이 UNSUPPORTED.
    var grid =
        new CharacterLoadout.ArkGrid(
            List.of(
                slot(0, "질서의 해 코어 : 직업 스킬", 20, "고대"),
                new CharacterLoadout.GridSlot(1, null, "질서의 달 코어 : 직업 스킬", null, null, List.of()),
                slot(2, "혼돈의 별 코어 : 무기", 20, "고대"),
                slot(3, "혼돈의 해 코어 : 신념의 강화", 20, "고대")),
            List.of(new CharacterLoadout.GridEffect("공격력", null)));
    var value = service.efficiency(dealer(grid));
    assertThat(value.status()).isEqualTo(Status.UNSUPPORTED);
    assertThat(value.percent()).isNull();
    assertThat(value.coresPercent()).isNull();
    assertThat(value.reasonCode()).isEqualTo("MODEL_UNCONFIRMED");
    assertThat(value.lines()).hasSize(5);
    assertThat(value.lines().get(0).status()).isEqualTo(Status.OK);
    assertThat(value.lines().get(0).percent()).isEqualByComparingTo("9.00");
    for (int i = 1; i <= 4; i++) {
      var line = value.lines().get(i);
      assertThat(line.status()).as(line.text()).isEqualTo(Status.UNSUPPORTED);
      assertThat(line.percent()).isNull();
      assertThat(line.reason()).isNotBlank();
      assertThat(line.reasonCode()).isEqualTo("MODEL_UNCONFIRMED");
    }
    assertThat(value.lines().get(2).reason()).contains("무기 코어");
  }

  @Test
  void 코어는_모두_계산하고_젬만_못_읽으면_코어_합은_준다() {
    var grid =
        new CharacterLoadout.ArkGrid(
            List.of(slot(0, "질서의 해 코어 : 직업 스킬", 20, "고대")),
            List.of(new CharacterLoadout.GridEffect("공격력", null)));
    var value = service.efficiency(dealer(grid));
    assertThat(value.status()).isEqualTo(Status.UNSUPPORTED);
    assertThat(value.percent()).isNull();
    assertThat(value.coresPercent()).isEqualByComparingTo("9.00");
  }

  @Test
  void 딜러가_아니거나_공식_전투력이_없으면_계산하지_않는다() {
    var grid =
        new CharacterLoadout.ArkGrid(List.of(slot(0, "질서의 해 코어 : 직업 스킬", 20, "고대")), List.of());
    // 바드는 깨달음 노드가 없으면 딜러·서포터를 판정하지 못한다.
    var unknown = service.efficiency(result("바드", new BigDecimal("3000"), grid));
    assertThat(unknown.status()).isEqualTo(Status.UNSUPPORTED);
    assertThat(unknown.reasonCode()).isEqualTo("ROLE_UNKNOWN");
    assertThat(unknown.lines()).allMatch(line -> line.status() == Status.UNSUPPORTED);
    var noPower = service.efficiency(result("블레이드", null, grid));
    assertThat(noPower.reasonCode()).isEqualTo("NO_OFFICIAL_COMBAT_POWER");
    assertThat(noPower.coresPercent()).isNull();
  }

  private static CharacterLoadout.GridSlot slot(int index, String name, int point, String grade) {
    return new CharacterLoadout.GridSlot(index, null, name, point, grade, List.of());
  }
}
