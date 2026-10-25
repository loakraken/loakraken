package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CombatPowerPolishValues;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.MarketAuctionService.PolishLine;
import com.loakraken.market.application.MarketAuctionService.PolishPrice;
import com.loakraken.market.application.MarketAuctionService.PolishStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/** 연마 교체 비용은 비교 기준 B와 같은 규칙: 목표 조합 시세 − 지금 조합 시세(거래 가능할 때), 역할 옵션 줄만. */
class SpecUpPolishCostTest {
  private static final Instant AT = Instant.parse("2026-10-25T01:04:23Z");

  private static CombatPowerPolishValues values() throws Exception {
    try (var stream = new ClassPathResource("combat-power/polish-values.json").getInputStream()) {
      return JsonMapper.builder().build().readValue(stream, CombatPowerPolishValues.class);
    }
  }

  private static CharacterLoadout.Equipment earring(List<String> tradeLines) {
    var item = mock(CharacterLoadout.Equipment.class);
    var detail = mock(CharacterLoadout.EquipmentDetail.class);
    when(item.type()).thenReturn("귀걸이");
    when(item.detail()).thenReturn(detail);
    when(detail.tradeLines()).thenReturn(tradeLines);
    return item;
  }

  @Test
  void 키는_역할_옵션_줄만이고_고정값_공격력은_상이_둘일_때만_남긴다() {
    var dealer =
        SpecUpPolishCost.keyLines(
            false, List.of("무기 공격력 +1.80%", "공격력 +80", "공격력 +0.40%"), false);
    assertThat(dealer)
        .containsExactly(
            new PolishLine("무기 공격력", true, "+1.80%"), new PolishLine("공격력", true, "+0.40%"));
    assertThat(SpecUpPolishCost.keyLines(false, List.of("무기 공격력 +3.00%", "공격력 +80"), true))
        .contains(new PolishLine("공격력", false, "+80"));
    // 서포터는 딜러 옵션 줄(공격력 %)을 빼고 서포터 옵션만
    assertThat(
            SpecUpPolishCost.keyLines(
                true, List.of("무기 공격력 +3.00%", "공격력 +0.40%", "파티원 보호막 효과 +3.50%"), false))
        .containsExactly(
            new PolishLine("무기 공격력", true, "+3.00%"),
            new PolishLine("파티원 보호막 효과", true, "+3.50%"));
    assertThat(SpecUpPolishCost.keyLines(false, List.of("낙인력 +8.00%"), false)).isNull();
  }

  @Test
  void 고정값_줄을_남기는_조건은_지금_장신구의_역할_옵션_상_둘이다() throws Exception {
    var values = values();
    assertThat(
            SpecUpPolishCost.keepFlat(
                false, "반지", List.of("치명타 적중률 +1.55%", "치명타 피해 +4.00%", "공격력 +80"), values))
        .isTrue();
    assertThat(
            SpecUpPolishCost.keepFlat(
                false, "반지", List.of("치명타 적중률 +1.55%", "치명타 피해 +2.40%", "공격력 +80"), values))
        .isFalse();
  }

  @Test
  void 거래_가능은_거래_제한_아이템_레벨_줄로_거래_불가는_그_줄로_알고_모르면_null() {
    assertThat(SpecUpPolishCost.tradeable(earring(List.of("거래 제한 아이템 레벨 1680")))).isTrue();
    assertThat(SpecUpPolishCost.tradeable(earring(List.of("거래 불가")))).isFalse();
    assertThat(SpecUpPolishCost.tradeable(earring(List.of()))).isNull();
  }

  @Test
  void 비용은_목표_시세에서_거래_가능한_지금_시세를_뺀다() throws Exception {
    // B 화면(계산 검증 캡처 10-25): 귀걸이 무공% 중 · 공% 하 → 무공% 중 · 공% 중 = 6,000 − 200 = 5,800.
    var auctions = mock(MarketAuctionService.class);
    var target =
        List.of(new PolishLine("무기 공격력", true, "+1.80%"), new PolishLine("공격력", true, "+0.95%"));
    var current =
        List.of(new PolishLine("무기 공격력", true, "+1.80%"), new PolishLine("공격력", true, "+0.40%"));
    when(auctions.polishPrice(eq("귀걸이"), eq(target), any()))
        .thenReturn(new PolishPrice(PolishStatus.OK, 6_000L, AT));
    when(auctions.polishPrice(eq("귀걸이"), eq(current), any()))
        .thenReturn(new PolishPrice(PolishStatus.OK, 200L, AT));
    var fill = new MarketAuctionService.PolishFill(false);
    var pricesAt = new Instant[1];
    var cost =
        SpecUpPolishCost.cost(
            auctions,
            fill,
            false,
            false,
            earring(List.of("거래 제한 아이템 레벨 1680")),
            List.of("무기 공격력 +1.80%", "공격력 +80", "공격력 +0.40%"),
            List.of("무기 공격력 +1.80%", "공격력 +0.95%"),
            values(),
            pricesAt);
    assertThat(cost.status()).isEqualTo("OK");
    assertThat(cost.code()).isEqualTo("POLISH_UPGRADE");
    assertThat(cost.amount()).isEqualTo(5_800L);
    assertThat(pricesAt[0]).isEqualTo(AT);
    // 거래 불가면 빼지 않는다
    var bound =
        SpecUpPolishCost.cost(
            auctions,
            fill,
            false,
            false,
            earring(List.of("거래 불가")),
            List.of("무기 공격력 +1.80%", "공격력 +80", "공격력 +0.40%"),
            List.of("무기 공격력 +1.80%", "공격력 +0.95%"),
            values(),
            new Instant[1]);
    assertThat(bound.code()).isEqualTo("POLISH_BUY");
    assertThat(bound.amount()).isEqualTo(6_000L);
  }

  @Test
  void 캐시에_칸이_없으면_스위치가_꺼져_있을_때_미확정_켜져_있을_때_PENDING이다() throws Exception {
    var auctions = mock(MarketAuctionService.class);
    when(auctions.polishPrice(any(), any(), any()))
        .thenReturn(new PolishPrice(PolishStatus.MISSING, null, null));
    for (boolean on : new boolean[] {false, true}) {
      var cost =
          SpecUpPolishCost.cost(
              auctions,
              new MarketAuctionService.PolishFill(on),
              on,
              false,
              earring(List.of("거래 불가")),
              List.of("무기 공격력 +1.80%", "공격력 +0.40%"),
              List.of("무기 공격력 +1.80%", "공격력 +0.95%"),
              values(),
              new Instant[1]);
      assertThat(cost.code()).as("스위치 " + on).isEqualTo(on ? "PRICE_PENDING" : "POLISH_COST_UNCONFIRMED");
    }
    // 매물 없음
    when(auctions.polishPrice(any(), any(), any()))
        .thenReturn(new PolishPrice(PolishStatus.OK, null, AT));
    var none =
        SpecUpPolishCost.cost(
            auctions,
            new MarketAuctionService.PolishFill(false),
            false,
            false,
            earring(List.of("거래 불가")),
            List.of("무기 공격력 +1.80%", "공격력 +0.40%"),
            List.of("무기 공격력 +1.80%", "공격력 +0.95%"),
            values(),
            new Instant[1]);
    assertThat(none.code()).isEqualTo("POLISH_NO_LISTING");
  }
}
