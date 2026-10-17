package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 연마 줄 배수(#325): 검증 표본 ref-a-polish-arkpassive-2026-10-11.json refAPct(줄 하나의 기여 %)와 같다. */
class CombatPowerPolishModelTest {
  @Test
  void 줄마다_독립_배수가_표본_기여와_같다() {
    var cases =
        Map.ofEntries(
            Map.entry("추가 피해|+2.60%", "1.99999"),
            // 추가 피해는 x/1.3(표본 resolved: 비교 기준 B 교체 값 0.70/1.3과 같음).
            Map.entry("추가 피해|+0.70%", "0.53846"),
            Map.entry("적에게 주는 피해|+2.00%", "1.99999"),
            Map.entry("적에게 주는 피해|+0.55%", "0.54999"),
            Map.entry("공격력|+390", "0.27298"),
            Map.entry("공격력|+80", "0.05597"),
            Map.entry("공격력|+1.55%", "1.54997"),
            Map.entry("공격력|+0.40%", "0.39998"),
            Map.entry("치명타 피해|+4.00%", "1.19999"),
            Map.entry("치명타 피해|+1.10%", "0.32998"),
            Map.entry("치명타 적중률|+1.55%", "1.2"),
            Map.entry("치명타 적중률|+0.95%", "0.73547"),
            Map.entry("치명타 적중률|+0.40%", "0.30966"));
    for (var entry : cases.entrySet()) {
      var key = entry.getKey().split("\\|");
      var line = CombatPowerPolishModel.line(key[0], key[1]);
      var pct = line.factor().subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100));
      assertThat(pct.subtract(new BigDecimal(entry.getValue())).abs())
          .as(entry.getKey())
          .isLessThanOrEqualTo(new BigDecimal("0.00005"));
    }
  }

  @Test
  void 무기_공격력은_W_항으로_넘기고_딜러에_0인_옵션은_배수_1이다() {
    var pct = CombatPowerPolishModel.line("무기 공격력", "+1.80%");
    assertThat(pct.factor()).isEqualByComparingTo("1");
    assertThat(pct.weaponPct()).isEqualByComparingTo("0.018");
    var flat = CombatPowerPolishModel.line("무기 공격력", "+960");
    assertThat(flat.weaponFlat()).isEqualByComparingTo("960");
    for (var option : new String[] {"최대 생명력", "낙인력", "아군 피해량 강화 효과", "세레나데, 신앙, 조화 게이지 획득량"})
      assertThat(CombatPowerPolishModel.line(option, "+1.00%").factor())
          .as(option)
          .isEqualByComparingTo("1");
  }

  @Test
  void 확인하지_않은_옵션이나_수치는_계산하지_않는다() {
    assertThat(CombatPowerPolishModel.line("치명타 적중률", "+0.50%")).isNull();
    assertThat(CombatPowerPolishModel.line("새 옵션", "+1.00%")).isNull();
    assertThat(CombatPowerPolishModel.line("추가 피해", "+390")).isNull();
    assertThat(CombatPowerPolishModel.line("공격력", "많이")).isNull();
  }
}
