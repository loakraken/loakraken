package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 장신구 기본 스탯 범위 %(흡수 #6): 공식 아이템 사전 연마 효과 탭의 부위·연마 단계별 힘민지 범위(시세 장신구 검색과 같은 표)로 계산한다. 연마 단계 = 연마 효과
 * 줄 수. 범위 밖이면 %는 null, 장신구가 아니거나 4티어 고대·유물이 아니거나 힘민지 줄이 없으면 전체 null.
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

  @Test
  void 목걸이_귀걸이_반지의_힘민지를_그_연마_단계_범위의_백분율로_낸다() {
    var necklace =
        AccessoryBaseStat.of(
            "목걸이", "고대", detail(4, 3, "힘 +16,009", "민첩 +16,009", "지능 +16,009", "체력 +3,500"));
    assertThat(necklace).isEqualTo(new AccessoryBaseStat(3, 16009, 15178, 17857, pct("31.02")));
    var earring = AccessoryBaseStat.of("귀걸이", "유물", detail(4, 2, "민첩 +12654", "체력 +2400"));
    assertThat(earring).isEqualTo(new AccessoryBaseStat(2, 12654, 10695, 12778, pct("94.05")));
    var ring = AccessoryBaseStat.of("반지", "고대", detail(4, 1, "지능 +9628"));
    assertThat(ring).isEqualTo(new AccessoryBaseStat(1, 9628, 9414, 11349, pct("11.06")));
    // 범위 양 끝.
    assertThat(AccessoryBaseStat.of("반지", "고대", detail(4, 0, "힘 +9156")).mainStatRangePct())
        .isEqualByComparingTo("0.00");
    assertThat(AccessoryBaseStat.of("반지", "고대", detail(4, 0, "힘 +11091")).mainStatRangePct())
        .isEqualByComparingTo("100.00");
  }

  @Test
  void 범위_밖_힘민지는_백분율만_null이고_나머지는_준다() {
    var high = AccessoryBaseStat.of("목걸이", "고대", detail(4, 0, "힘 +15358"));
    assertThat(high).isEqualTo(new AccessoryBaseStat(0, 15358, 12678, 15357, null));
    assertThat(high.outOfRange()).isTrue();
    var low = AccessoryBaseStat.of("목걸이", "고대", detail(4, 3, "힘 +15177"));
    assertThat(low.mainStatRangePct()).isNull();
  }

  @Test
  void 장신구가_아니거나_해석할_수_없으면_null이다() {
    assertThat(AccessoryBaseStat.of("팔찌", "고대", detail(4, 0, "힘 +12000"))).isNull();
    assertThat(AccessoryBaseStat.of("무기", "고대", detail(4, 0, "힘 +12000"))).isNull();
    assertThat(AccessoryBaseStat.of("목걸이", "고대", detail(3, 0, "힘 +12000"))).isNull();
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
