package com.loakraken.refining.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 장비 성장(경험치) 비용(#200). 값은 실제 리소스(계산 검증 2026-10-10 장비 성장 비용 표)를 쓴다. */
class GrowthCostTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static <T> T read(String resource, Class<T> type) throws IOException {
    try (var input = GrowthCostTest.class.getResourceAsStream(resource)) {
      return MAPPER.readValue(input, type);
    }
  }

  private static RefiningPlan.Options options(
      int from, int to, boolean growthCost, boolean expResearch) {
    return new RefiningPlan.Options(from, to, false, false, true, 0, 0, growthCost, expResearch);
  }

  @Test
  void 성장_파편은_단계당_한_번_재료에_더하고_영지_연구는_업화_11_14단계에만_80퍼센트다() throws IOException {
    var rules = read("/refining/t4-refining-rules.json", RefiningRules.class);
    var growth = read("/refining/t4-growth-costs.json", GrowthCosts.class);
    var track = rules.track(RefiningRules.Grade.UPHWA, RefiningRules.EquipmentType.ARMOR);
    var none =
        RefiningPlan.calculate(
            track, RefiningRules.Grade.UPHWA, options(10, 15, false, false), growth);
    var off =
        RefiningPlan.calculate(
            track, RefiningRules.Grade.UPHWA, options(10, 15, true, false), growth);
    var on =
        RefiningPlan.calculate(
            track, RefiningRules.Grade.UPHWA, options(10, 15, true, true), growth);

    // 업화 방어구 11~15 성장 파편: 12,000 + 13,000 + 19,000 + 22,000 + 25,000 = 91,000(일로아 10→11 12,000과 같음)
    assertThat(shard(off).subtract(shard(none))).isEqualByComparingTo("91000");
    // 영지 연구: 11~14단계 × 0.8 → 9,600 + 10,400 + 15,200 + 17,600 + 25,000 = 77,800
    assertThat(shard(on).subtract(shard(none))).isEqualByComparingTo("77800");
    var first = on.stages().getFirst().growth();
    assertThat(first.shard()).isEqualByComparingTo("9600");
    assertThat(first.silver()).isEqualTo(300_000);
    assertThat(first.research()).isTrue();
    assertThat(on.stages().getLast().growth().research()).isFalse();
    // 성장 비용은 시도 수와 관계없어 최악 수량에도 같은 값이 더해진다.
    assertThat(worstShard(off).subtract(worstShard(none))).isEqualByComparingTo("91000");
    // 실링은 재료 합계에 넣지 않는다.
    assertThat(off.expectedMaterials()).noneMatch(q -> q.name().contains("실링"));
    // 골드는 성장 비용과 관계없다.
    assertThat(off.expectedGold()).isEqualByComparingTo(none.expectedGold());
  }

  @Test
  void 사이트가_갈린_전율_21단계는_성장_비용을_빼고_미확정으로_남긴다() throws IOException {
    var rules = read("/refining/t4-refining-rules.json", RefiningRules.class);
    var growth = read("/refining/t4-growth-costs.json", GrowthCosts.class);
    var track = rules.track(RefiningRules.Grade.JEONYUL, RefiningRules.EquipmentType.WEAPON);
    var plan =
        RefiningPlan.calculate(
            track, RefiningRules.Grade.JEONYUL, options(20, 22, true, true), growth);
    var stage21 = plan.stages().getFirst().growth();
    assertThat(stage21.confirmed()).isFalse();
    assertThat(stage21.shard()).isEqualByComparingTo(BigDecimal.ZERO);
    var stage22 = plan.stages().getLast().growth();
    assertThat(stage22.confirmed()).isTrue();
    assertThat(stage22.shard()).isEqualByComparingTo("128000");
    // 전율에는 영지 연구가 걸리지 않는다.
    assertThat(stage22.research()).isFalse();
  }

  private static BigDecimal shard(RefiningPlan plan) {
    return plan.expectedMaterials().stream()
        .filter(q -> q.name().equals(RefiningPlan.SHARD))
        .map(RefiningPlan.Quantity::amount)
        .findFirst()
        .orElseThrow();
  }

  private static BigDecimal worstShard(RefiningPlan plan) {
    return plan.worstCaseMaterials().stream()
        .filter(q -> q.name().equals(RefiningPlan.SHARD))
        .map(RefiningPlan.Quantity::amount)
        .findFirst()
        .orElseThrow();
  }
}
