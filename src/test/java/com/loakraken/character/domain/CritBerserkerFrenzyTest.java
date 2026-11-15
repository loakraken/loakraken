package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CritSpeedCalculation.Assumptions;
import com.loakraken.character.domain.CritSpeedCalculation.Axis;
import com.loakraken.character.domain.CritSpeedCalculation.Category;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 버서커 폭주(치적·공속 표 고치기 A, PM 총괄 11-16 D1): 기본 치명타 적중률 30 × (1 + 특화 툴팁 '폭주 효과가 X% 증폭됩니다'), 공식 툴팁 값 기준.
 * 직업 버서커이고 그 툴팁 줄이 있고 깨달음 '광기' 노드가 없을 때만, 기본 켬.
 */
class CritBerserkerFrenzyTest {
  @Test
  void 특화_툴팁_증폭만큼_폭주_치명타_적중률을_더한다() {
    var result = calculate("버서커", "폭주 효과가 63.01% 증폭됩니다.", List.of());
    // 30 × 1.6301 = 48.903 → 48.90
    assertThat(values(result)).containsExactly("폭주 상태 가정(특화 증폭)=48.9");
    assertThat(result.toggles())
        .extracting(t -> t.key() + "/" + t.coreState() + "/" + t.on())
        .contains("폭주 상태 가정(특화 증폭)/true/true");
    // 표시 문구는 폭주 증폭 줄만(특화 툴팁 전체가 아님, 계산 검증 #981 P3).
    assertThat(result.toggles())
        .filteredOn(t -> t.key().equals("폭주 상태 가정(특화 증폭)"))
        .extracting(CritSpeedCalculation.ToggleState::text)
        .containsExactly("폭주 효과가 63.01% 증폭됩니다.");
  }

  @Test
  void 광기_노드가_있거나_툴팁_줄이_없거나_다른_직업이면_더하지_않는다() {
    var madness =
        calculate(
            "버서커",
            "폭주 효과가 63.01% 증폭됩니다.",
            List.of(
                new CharacterLoadout.PassiveEffect(
                    "깨달음", "깨달음 1티어 광기 Lv.3", null, "치명타 적중률이 25.0% 증가한다.")));
    assertThat(values(madness)).isEmpty();
    assertThat(madness.limitations()).anyMatch(line -> line.contains("광기 버서커"));
    assertThat(values(calculate("버서커", "치명타 피해가 증가합니다.", List.of()))).isEmpty();
    assertThat(values(calculate("슬레이어", "폭주 효과가 63.01% 증폭됩니다.", List.of())))
        .doesNotContain("폭주 상태 가정(특화 증폭)=48.9");
  }

  @Test
  void 끄면_더하지_않는다() {
    var off =
        CritSpeedCalculation.calculate(
            profile("버서커", "폭주 효과가 63.01% 증폭됩니다."),
            loadout(List.of()),
            new Assumptions(
                false,
                false,
                0,
                null,
                null,
                null,
                null,
                null,
                true,
                java.util.Map.of("폭주 상태 가정(특화 증폭)", false)));
    assertThat(values(off)).isEmpty();
  }

  private static List<String> values(CritSpeedCalculation.Result result) {
    return result.sources().stream()
        .filter(source -> source.axis() == Axis.CRIT && source.category() == Category.ASSUMPTION)
        .filter(source -> source.name().startsWith("폭주 상태 가정(특화"))
        .map(source -> source.name() + "=" + source.value().stripTrailingZeros().toPlainString())
        .toList();
  }

  private static CritSpeedCalculation.Result calculate(
      String className, String specTooltip, List<CharacterLoadout.PassiveEffect> nodes) {
    return CritSpeedCalculation.calculate(
        profile(className, specTooltip), loadout(nodes), Assumptions.NONE);
  }

  private static CharacterProfile profile(String className, String specTooltip) {
    return new CharacterProfile(
        "표본",
        "카단",
        className,
        70,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(
            new CharacterProfile.Stat("치명", "576", List.of("치명타 적중률이 20.61% 증가합니다.")),
            new CharacterProfile.Stat(
                "특화", "1694", List.of("아이덴티티 게이지 획득량이 증가합니다.", specTooltip)),
            new CharacterProfile.Stat(
                "신속", "77", List.of("공격 속도가 1.32% 증가합니다.", "이동 속도가 1.32% 증가합니다."))));
  }

  private static CharacterLoadout loadout(List<CharacterLoadout.PassiveEffect> nodes) {
    return new CharacterLoadout(
        List.of(),
        null,
        null,
        null,
        new CharacterLoadout.ArkPassive("각인", true, List.of(), nodes),
        null,
        List.of());
  }
}
