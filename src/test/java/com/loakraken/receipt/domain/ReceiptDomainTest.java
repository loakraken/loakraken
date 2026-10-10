package com.loakraken.receipt.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.refining.domain.GrowthCosts;
import com.loakraken.refining.domain.RefiningRules;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 원정대 영수증 계산 규칙. 재련 규칙은 실제 리소스(t4-refining-v2)를 쓴다. */
class ReceiptDomainTest {
  private static final RefiningRules RULES =
      JsonMapper.builder()
          .build()
          .readValue(
              ReceiptDomainTest.class.getResourceAsStream("/refining/t4-refining-rules.json"),
              RefiningRules.class);

  @Test
  void 부위마다_업화는_10단계_전율은_11단계부터_현재까지_단계별_기대_비용을_더하고_제외_사유를_남긴다() {
    var items =
        List.of(
            item("무기", "+19 운명의 전율 검", 19, 4),
            item("투구", "+18 운명의 업화 머리장식", 18, 4),
            item("상의", "+9 운명의 업화 상의", 9, 4),
            item("하의", "+20 빛나는 하의", 20, 3),
            item("완갑", "+13 운명의 전율 완갑", 13, 4),
            item("목걸이", "목걸이", null, 4));
    var prices = prices(BigDecimal.ONE);
    var result = EquipmentReceipt.calculate(RULES, items, prices);

    assertThat(result.parts())
        .extracting(EquipmentReceipt.Part::slot)
        .containsExactly("무기", "투구", "상의");
    var weapon = result.parts().get(0);
    assertThat(weapon.grade()).isEqualTo(RefiningRules.Grade.JEONYUL);
    // 전율은 +11부터 센다(두 사이트, #218 A).
    assertThat(weapon.fromStage()).isEqualTo(11);
    assertThat(weapon.toStage()).isEqualTo(19);
    assertThat(weapon.stages()).hasSize(8);
    assertThat(weapon.gold()).isPositive();
    assertThat(weapon.stages()).noneMatch(EquipmentReceipt.StageCost::growthSupport);
    var helmet = result.parts().get(1);
    assertThat(helmet.grade()).isEqualTo(RefiningRules.Grade.UPHWA);
    // 업화 18단계 이하는 성장 지원
    assertThat(helmet.stages()).allMatch(EquipmentReceipt.StageCost::growthSupport);
    var top = result.parts().get(2);
    assertThat(top.gold()).isZero();
    assertThat(top.stages()).isEmpty();
    assertThat(result.excluded())
        .extracting(EquipmentReceipt.Excluded::reason)
        .containsExactly(
            EquipmentReceipt.Reason.UNSUPPORTED_GRADE, EquipmentReceipt.Reason.ARM_GUARD);
    // 고정 예시("결단 등") 대신 그 부위 장비 이름으로 알린다.
    assertThat(result.excluded().getFirst().message())
        .isEqualTo("「+20 빛나는 하의」: 업화·전율 장비가 아니라 산정하지 않습니다.");
    assertThat(result.gold()).isEqualByComparingTo(weapon.gold().add(helmet.gold()));
  }

  @Test
  void 단계마다_숨결_사용_여부_중_싼_쪽을_고른다() {
    var items = List.of(item("무기", "+15 운명의 전율 검", 15, 4));
    var names = EquipmentReceipt.materialNames(RULES, items);
    // 숨결이 아주 비싸면 쓰지 않는다.
    var expensive = prices(BigDecimal.ONE);
    names.stream()
        .filter(name -> name.contains("숨결"))
        .forEach(name -> expensive.put(name, new BigDecimal("1000000")));
    var noBreath = EquipmentReceipt.calculate(RULES, items, expensive).parts().getFirst();
    assertThat(noBreath.stages()).noneMatch(EquipmentReceipt.StageCost::breath);
    // 숨결이 공짜면 쓴다(성공률이 오르고 비용은 늘지 않으므로).
    var free = prices(new BigDecimal("100"));
    names.stream()
        .filter(name -> name.contains("숨결"))
        .forEach(name -> free.put(name, BigDecimal.ZERO));
    var withBreath = EquipmentReceipt.calculate(RULES, items, free).parts().getFirst();
    assertThat(withBreath.stages()).allMatch(EquipmentReceipt.StageCost::breath);
    // 고른 쪽은 다른 쪽보다 비싸지 않다: 숨결 공짜일 때 합계 ≤ 숨결 금지 가격의 합계
    var forbid = prices(new BigDecimal("100"));
    names.stream()
        .filter(name -> name.contains("숨결"))
        .forEach(name -> forbid.put(name, new BigDecimal("1000000")));
    assertThat(withBreath.gold())
        .isLessThanOrEqualTo(
            EquipmentReceipt.calculate(RULES, items, forbid).parts().getFirst().gold());
  }

  /** PM 총괄 10-10 03:00: 책 발동 확률이 공개되지 않아 기본은 책 제외, assumeBook이면 책 효과 항상 적용 가정. */
  @Test
  void 책은_기본으로_쓰지_않고_가정을_켜면_더_싼_쪽으로_쓴다() {
    var items = List.of(item("투구", "+19 운명의 업화 투구", 19, 4));
    var names = EquipmentReceipt.materialNames(RULES, items);
    // 책이 공짜면 가정을 켰을 때 쓴다.
    var freeBook = prices(new BigDecimal("100"));
    names.stream()
        .filter(name -> name.contains("재봉술"))
        .forEach(name -> freeBook.put(name, BigDecimal.ZERO));
    var off = EquipmentReceipt.calculate(RULES, items, freeBook).parts().getFirst();
    assertThat(off.stages()).noneMatch(EquipmentReceipt.StageCost::book);
    var on = EquipmentReceipt.calculate(RULES, items, freeBook, true).parts().getFirst();
    assertThat(on.stages()).anyMatch(EquipmentReceipt.StageCost::book);
    assertThat(on.gold()).isLessThan(off.gold());
    // 책을 빼도(업화) 숨결 사용·미사용 중 싼 쪽은 그대로 고른다.
    var freeBreath = prices(new BigDecimal("100"));
    names.stream()
        .filter(name -> name.contains("숨결"))
        .forEach(name -> freeBreath.put(name, BigDecimal.ZERO));
    var breath = EquipmentReceipt.calculate(RULES, items, freeBreath).parts().getFirst();
    assertThat(breath.stages())
        .allMatch(EquipmentReceipt.StageCost::breath)
        .noneMatch(EquipmentReceipt.StageCost::book);
  }

  /** #200: 성장 파편을 더하고, 사이트가 갈린 전율 21단계는 빼고 부위에 미확정 사유를 남긴다. */
  @Test
  void 성장_비용을_더하고_전율_21단계_한계_해방은_산정하지_않았다고_알린다() throws Exception {
    GrowthCosts growth;
    try (var input =
        ReceiptDomainTest.class.getResourceAsStream("/refining/t4-growth-costs.json")) {
      growth =
          tools.jackson.databind.json.JsonMapper.builder()
              .build()
              .readValue(input, GrowthCosts.class);
    }
    var items = List.of(item("무기", "+22 운명의 전율 검", 22, 4));
    var prices = prices(BigDecimal.ONE);
    var without = EquipmentReceipt.calculate(RULES, items, prices, false, null);
    var with = EquipmentReceipt.calculate(RULES, items, prices, false, growth);
    // 전율 무기 성장 파편 12~20·22단계(21 제외): 57+63+70+76+83+90+98+105+112+128 = 882,000, 파편 1개 1G
    assertThat(with.gold().subtract(without.gold())).isEqualByComparingTo("882000");
    assertThat(with.excluded())
        .extracting(EquipmentReceipt.Excluded::reason)
        .doesNotContain(EquipmentReceipt.Reason.GROWTH_COST_UNCONFIRMED);
    assertThat(with.excluded())
        .filteredOn(e -> e.reason() == EquipmentReceipt.Reason.LIMIT_BREAK_UNPRICED)
        .extracting(EquipmentReceipt.Excluded::message)
        .containsExactly("「+22 운명의 전율 검」: 21단계 한계 해방 비용(고통의 가시·해방 비용)은 공식 수치가 없어 산정하지 않았습니다.");
  }

  /** 공식 Tooltip "[상급 재련] N단계"(equipment-tooltip-v5). 1단계 이상이면 부위마다, 모르는 이전 캐시면 캐릭터마다 한 줄. */
  @Test
  void 상급_재련은_단계가_있는_부위마다_알리고_모르면_한_줄로_알린다() {
    var known =
        EquipmentReceipt.calculate(
            RULES,
            List.of(
                item("투구", "+18 운명의 업화 머리장식", 18, 4, 28), item("상의", "+19 운명의 업화 상의", 19, 4, 0)),
            prices(BigDecimal.ONE));
    assertThat(known.excluded())
        .extracting(EquipmentReceipt.Excluded::message)
        .containsExactly("「+18 운명의 업화 머리장식」 상급 재련 28단계: 아직 산정하지 않습니다(재련 v2 준비 중).");
    var unknown =
        EquipmentReceipt.calculate(
            RULES, List.of(item("투구", "+18 운명의 업화 머리장식", 18, 4, null)), prices(BigDecimal.ONE));
    assertThat(unknown.excluded())
        .extracting(EquipmentReceipt.Excluded::message)
        .containsExactly("상급 재련은 아직 산정하지 않습니다(재련 v2 준비 중).");
  }

  @Test
  void 재료_시세가_없으면_그_부위는_PRICE_UNAVAILABLE이다() {
    var items = List.of(item("무기", "+12 운명의 전율 검", 12, 4));
    var result = EquipmentReceipt.calculate(RULES, items, Map.of());
    assertThat(result.parts()).isEmpty();
    assertThat(result.excluded())
        .extracting(EquipmentReceipt.Excluded::reason)
        .containsExactly(EquipmentReceipt.Reason.PRICE_UNAVAILABLE);
    assertThat(result.gold()).isZero();
  }

  @Test
  void 보석은_귀속을_개수만_세고_시세가_있는_거래_가능분만_더한다() {
    var gems =
        List.of(
            new CharacterLoadout.Gems(
                List.of(
                    gem(0, "10레벨 겁화의 보석", 10),
                    gem(1, "10레벨 겁화의 보석", 10),
                    gem(2, "8레벨 작열의 보석 (귀속)", 8),
                    gem(3, "6레벨 광휘의 보석", 6),
                    gem(4, "9레벨 작열의 보석", 9)),
                null));
    assertThat(GemReceipt.tradableKeys(gems))
        .containsExactly(
            new GemReceipt.Key("겁화", 10), new GemReceipt.Key("광휘", 6), new GemReceipt.Key("작열", 9));
    var prices =
        Map.of(
            new GemReceipt.Key("겁화", 10), new GemReceipt.Price(new BigDecimal("2500000"), false),
            new GemReceipt.Key("작열", 9), new GemReceipt.Price(null, true));
    var result = GemReceipt.calculate(gems, prices);
    assertThat(result.tradable()).isEqualTo(4);
    assertThat(result.priced()).isEqualTo(2);
    assertThat(result.bound()).isEqualTo(1);
    assertThat(result.unpriced()).isEqualTo(2);
    assertThat(result.gold()).isEqualByComparingTo("5000000");
    assertThat(result.items())
        .extracting(GemReceipt.Item::status)
        .containsExactly(
            GemReceipt.Status.PRICED, GemReceipt.Status.NO_PRICE, GemReceipt.Status.PENDING);
  }

  private static HashMap<String, BigDecimal> prices(BigDecimal each) {
    var all =
        List.of(
            item("무기", "+25 운명의 전율 검", 25, 4),
            item("투구", "+25 운명의 전율 투구", 25, 4),
            item("무기", "+25 운명의 업화 검", 25, 4),
            item("투구", "+25 운명의 업화 투구", 25, 4));
    var map = new HashMap<String, BigDecimal>();
    for (var name : EquipmentReceipt.materialNames(RULES, all)) map.put(name, each);
    return map;
  }

  private static CharacterLoadout.Equipment item(
      String type, String name, Integer refine, int tier) {
    return item(type, name, refine, tier, refine == null || tier != 4 ? null : 0);
  }

  /** advanced: 상급 재련 단계(null이면 이전 캐시처럼 모름). */
  private static CharacterLoadout.Equipment item(
      String type, String name, Integer refine, int tier, Integer advanced) {
    return new CharacterLoadout.Equipment(
        type,
        name,
        null,
        "고대",
        new CharacterLoadout.EquipmentDetail(
            refine, 100, 1700, tier, null, List.of(), List.of(), List.of(), List.of(), null,
            List.of(), null, List.of(), advanced));
  }

  private static CharacterLoadout.Gem gem(int slot, String name, int level) {
    return new CharacterLoadout.Gem(slot, name, null, level, "전설");
  }
}
