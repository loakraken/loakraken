package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 장신구 기본 스탯 범위 %(흡수 #6, 비교 기준과 같은 규칙): 공식 아이템 사전 표의 그 부위 3단계(연마 뒤) 힘민지 범위로 잰다. 범위 밖은 음수·100 초과 그대로.
 * 연마 단계 = 연마 효과 줄 수(따로 알려 줌). 장신구가 아니거나 3·4티어 고대·유물이 아니거나 힘민지 줄이 없으면 전체 null.
 */
class AccessoryBaseStatTest {
  private static CharacterLoadout.EquipmentDetail detail(
      Integer tier, int polishLines, String... baseLines) {
    var polish = new ArrayList<CharacterLoadout.PolishEffect>();
    for (int i = 0; i < polishLines; i++)
      polish.add(
          new CharacterLoadout.PolishEffect("추가 피해", "+0.70%", CharacterLoadout.EffectGrade.LOW));
    return new CharacterLoadout.EquipmentDetail(
        null,
        67,
        null,
        tier,
        null,
        List.of(
            new CharacterLoadout.EffectGroup("기본 효과", List.of(baseLines)),
            new CharacterLoadout.EffectGroup("연마 효과", List.of())),
        List.of(),
        polish,
        List.of(),
        null,
        List.of(),
        null,
        List.of(),
        null);
  }

  /**
   * 비교 기준과 같은 규칙(PM 결정 11-09): 연마 단계와 상관없이 그 부위의 3단계(연마 뒤) 범위로 잰다. 범위 아래는 음수, 위는 100 초과 그대로. 연마 단계는
   * 따로 알려 준다.
   */
  @Test
  void 연마_단계와_상관없이_그_부위_3단계_범위의_백분율로_낸다() {
    var necklace =
        AccessoryBaseStat.of(
            "목걸이", "고대", detail(4, 3, "힘 +16,009", "민첩 +16,009", "지능 +16,009", "체력 +3,500"));
    assertThat(necklace).isEqualTo(new AccessoryBaseStat(3, 16009, 15178, 17857, pct("31.02")));
    // 2단계 목걸이 15527(같은 식으로 계산한 값, 판독 아님): 3단계 범위로 13.03%(단계별 범위였다면 66.37%).
    var stageTwo = AccessoryBaseStat.of("목걸이", "고대", detail(4, 2, "힘 +15527"));
    assertThat(stageTwo).isEqualTo(new AccessoryBaseStat(2, 15527, 15178, 17857, pct("13.03")));
    var earring = AccessoryBaseStat.of("귀걸이", "유물", detail(4, 2, "민첩 +12654", "체력 +2400"));
    assertThat(earring).isEqualTo(new AccessoryBaseStat(2, 12654, 11806, 13889, pct("40.71")));
    // 1단계 반지는 3단계 최솟값보다 낮아 음수.
    var ring = AccessoryBaseStat.of("반지", "고대", detail(4, 1, "지능 +9628"));
    assertThat(ring).isEqualTo(new AccessoryBaseStat(1, 9628, 10962, 12897, pct("-68.94")));
    // 3단계 범위 양 끝.
    assertThat(AccessoryBaseStat.of("반지", "고대", detail(4, 0, "힘 +10962")).mainStatRangePct())
        .isEqualByComparingTo("0.00");
    assertThat(AccessoryBaseStat.of("반지", "고대", detail(4, 3, "힘 +12897")).mainStatRangePct())
        .isEqualByComparingTo("100.00");
  }

  /** 비교 기준에서 읽은 값(계산 검증 11-08, 레퍼런스 판독). 3티어 유물 장신구도 같은 3단계 범위로 음수가 나온다. */
  @Test
  void 비교_기준에서_읽은_값과_같다() {
    record Row(String slot, int tier, int lines, int stat, String pct) {}
    for (var row :
        List.of(
            new Row("목걸이", 4, 3, 16009, "31.02"),
            new Row("귀걸이", 4, 3, 13723, "92.03"),
            new Row("귀걸이", 4, 3, 13744, "93.04"),
            new Row("반지", 4, 3, 12220, "65.01"),
            new Row("반지", 4, 3, 12046, "56.02"),
            new Row("귀걸이", 3, 0, 6031, "-277.24"),
            new Row("반지", 3, 0, 6362, "-237.73"),
            new Row("귀걸이", 3, 2, 9006, "-134.42"))) {
      var value =
          AccessoryBaseStat.of(
              row.slot(), "유물", detail(row.tier(), row.lines(), "힘 +" + row.stat()));
      assertThat(value).as(row.toString()).isNotNull();
      assertThat(value.mainStatRangePct()).as(row.toString()).isEqualByComparingTo(row.pct());
    }
    // 같은 식으로 계산한 범위 위 값(판독 아님).
    assertThat(AccessoryBaseStat.of("반지", "고대", detail(4, 3, "힘 +12898")).mainStatRangePct())
        .isEqualByComparingTo("100.05");
    assertThat(AccessoryBaseStat.of("목걸이", "고대", detail(4, 3, "힘 +17900")).mainStatRangePct())
        .isEqualByComparingTo("101.61");
  }

  @Test
  void 범위_밖_힘민지도_백분율을_그대로_내고_범위표_점검_대상을_표시한다() {
    var high = AccessoryBaseStat.of("목걸이", "고대", detail(4, 3, "힘 +18000"));
    assertThat(high).isEqualTo(new AccessoryBaseStat(3, 18000, 15178, 17857, pct("105.34")));
    assertThat(high.rangeSuspect()).isTrue();
    var low = AccessoryBaseStat.of("목걸이", "고대", detail(4, 3, "힘 +15177"));
    assertThat(low.mainStatRangePct()).isEqualByComparingTo("-0.04");
    // 연마 3단계인데 최솟값 아래면 범위표 점검 대상
    assertThat(low.rangeSuspect()).isTrue();
    assertThat(AccessoryBaseStat.of("목걸이", "고대", detail(4, 2, "힘 +15177")).rangeSuspect())
        .isFalse();
    assertThat(AccessoryBaseStat.of("목걸이", "고대", detail(4, 3, "힘 +16000")).rangeSuspect())
        .isFalse();
  }

  @Test
  void 장신구가_아니거나_해석할_수_없으면_null이다() {
    assertThat(AccessoryBaseStat.of("팔찌", "고대", detail(4, 0, "힘 +12000"))).isNull();
    assertThat(AccessoryBaseStat.of("무기", "고대", detail(4, 0, "힘 +12000"))).isNull();
    assertThat(AccessoryBaseStat.of("목걸이", "고대", detail(2, 0, "힘 +12000"))).isNull();
    assertThat(AccessoryBaseStat.of("목걸이", "전설", detail(4, 0, "힘 +12000"))).isNull();
    assertThat(AccessoryBaseStat.of("목걸이", "고대", detail(4, 0, "체력 +3500"))).isNull();
    assertThat(AccessoryBaseStat.of("목걸이", "고대", detail(4, 4, "힘 +16000"))).isNull();
    assertThat(AccessoryBaseStat.of("목걸이", "고대", null)).isNull();
    assertThat(AccessoryBaseStat.of(null, "고대", detail(4, 0, "힘 +12000"))).isNull();
  }

  private static java.math.BigDecimal pct(String value) {
    return new java.math.BigDecimal(value);
  }
}
