package com.loakraken.specup.application;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CombatPowerPolishValues;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.MarketAuctionService.PolishLine;
import com.loakraken.market.application.MarketAuctionService.PolishPrice;
import com.loakraken.market.application.MarketAuctionService.PolishStatus;
import com.loakraken.specup.domain.SpecUpCost;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 장신구 연마 교체 비용(비교 기준 B와 같은 규칙, 계산 검증 10-25): 목표 조합 시세 − 지금 조합 시세(지금 장신구가 거래 가능할 때만), 0 미만은 0. 조합은 그
 * 역할의 옵션 줄만(B 옵션 표의 역할 표시) — 고정값 공격력·무기 공격력 줄은 지금 장신구의 그 역할 옵션(고정값 제외) 상이 둘 이상일 때만 넣는다. 시세 칸은
 * 고대·4티어·품질 조건 없음(MarketAuctionService.polishPrice).
 */
final class SpecUpPolishCost {
  private SpecUpPolishCost() {}

  private static final Pattern LINE = Pattern.compile("(.+?) \\+([\\d.,]+)(%?)");
  private static final Pattern TRADE_ITEM_LEVEL = Pattern.compile("거래 제한 아이템 레벨");

  /** B 옵션 표에서 딜러 옵션인 줄(이름 + %면 "%"). */
  private static final Set<String> DEALER =
      Set.of("추가 피해%", "적에게 주는 피해%", "공격력%", "무기 공격력%", "치명타 적중률%", "치명타 피해%", "공격력", "무기 공격력");

  /** B 옵션 표에서 서포터 옵션인 줄. */
  private static final Set<String> SUPPORT =
      Set.of(
          "낙인력%",
          "세레나데, 신앙, 조화 게이지 획득량%",
          "무기 공격력%",
          "파티원 회복 효과%",
          "파티원 보호막 효과%",
          "아군 공격력 강화 효과%",
          "아군 피해량 강화 효과%",
          "무기 공격력",
          "최대 생명력",
          "최대 마나");

  /** B 옵션 표에 있는 줄(4티어 고대). 표에 없는 줄은 B처럼 역할 옵션으로 본다(그러면 시세 칸으로 나타낼 수 없어 미확정). */
  private static final Set<String> KNOWN = known();

  private static Set<String> known() {
    var all = new java.util.HashSet<String>(DEALER);
    all.addAll(SUPPORT);
    all.add("상태이상 공격 지속시간%");
    all.add("전투 중 생명력 회복량");
    return Set.copyOf(all);
  }

  static SpecUpCost cost(
      MarketAuctionService auctions,
      MarketAuctionService.PolishFill fill,
      boolean fillEnabled,
      boolean support,
      CharacterLoadout.Equipment item,
      List<String> current,
      List<String> next,
      CombatPowerPolishValues values,
      Instant[] pricesAt) {
    boolean keepFlat = keepFlat(support, item.type(), current, values);
    var target = keyLines(support, next, keepFlat);
    if (target == null) return SpecUpCost.POLISH_COST_UNCONFIRMED;
    var tradeable = tradeable(item);
    if (tradeable == null) return SpecUpCost.POLISH_COST_UNCONFIRMED;
    var targetPrice = auctions.polishPrice(item.type(), target, fill);
    if (targetPrice.status() != PolishStatus.OK) return missing(targetPrice, fillEnabled);
    if (targetPrice.minBuyPrice() == null) return SpecUpCost.POLISH_NO_LISTING;
    long amount = targetPrice.minBuyPrice();
    var observed = targetPrice.observedAt();
    if (tradeable) {
      var mine = keyLines(support, current, keepFlat);
      if (mine == null) return SpecUpCost.POLISH_COST_UNCONFIRMED;
      var currentPrice = auctions.polishPrice(item.type(), mine, fill);
      if (currentPrice.status() != PolishStatus.OK) return missing(currentPrice, fillEnabled);
      if (currentPrice.minBuyPrice() == null) return SpecUpCost.POLISH_COST_UNCONFIRMED;
      amount = Math.max(0, amount - currentPrice.minBuyPrice());
      observed = earlier(observed, currentPrice.observedAt());
    }
    if (observed != null && (pricesAt[0] == null || observed.isBefore(pricesAt[0])))
      pricesAt[0] = observed;
    return SpecUpCost.polish(amount, tradeable);
  }

  private static SpecUpCost missing(PolishPrice price, boolean fillEnabled) {
    return price.status() == PolishStatus.MISSING && fillEnabled
        ? SpecUpCost.POLISH_PRICE_PENDING
        : SpecUpCost.POLISH_COST_UNCONFIRMED;
  }

  /**
   * B 매물 키의 줄: 그 역할 옵션 줄(표에 없는 줄 포함), 고정값 공격력·무기 공격력은 keepFlat일 때만. 줄이 없으면 null(B도 키가 없다).
   */
  static List<PolishLine> keyLines(boolean support, List<String> lines, boolean keepFlat) {
    var role = support ? SUPPORT : DEALER;
    var key = new ArrayList<PolishLine>();
    for (var line : lines) {
      var m = LINE.matcher(line);
      if (!m.matches()) {
        // 모양을 모르는 줄은 표에 없는 줄이라 역할 옵션으로 본다(시세 칸으로 나타낼 수 없음).
        key.add(new PolishLine(line, false, ""));
        continue;
      }
      boolean percent = !m.group(3).isEmpty();
      var id = m.group(1) + (percent ? "%" : "");
      boolean known = KNOWN.contains(id);
      if (known && !role.contains(id)) continue;
      if (!percent && isFlatAttack(m.group(1)) && !keepFlat) continue;
      key.add(new PolishLine(m.group(1), percent, "+" + m.group(2) + m.group(3)));
    }
    return key.isEmpty() ? null : List.copyOf(key);
  }

  /** 지금 장신구의 그 역할 옵션(고정값 공격력·무기 공격력 제외) 가운데 상이 둘 이상인가(B: 고정값 줄을 키에 남기는 조건). */
  static boolean keepFlat(
      boolean support, String part, List<String> current, CombatPowerPolishValues values) {
    var role = support ? SUPPORT : DEALER;
    int high = 0;
    for (var line : current) {
      var m = LINE.matcher(line);
      if (!m.matches()) continue;
      boolean percent = !m.group(3).isEmpty();
      if (!percent && isFlatAttack(m.group(1))) continue;
      if (!role.contains(m.group(1) + (percent ? "%" : ""))) continue;
      if (SpecUpScoreCandidates.gradeOf(values, part, m.group(1), percent, line) == 2) high++;
    }
    return high >= 2;
  }

  private static boolean isFlatAttack(String name) {
    return "공격력".equals(name) || "무기 공격력".equals(name);
  }

  /**
   * 지금 장신구를 거래할 수 있는가. 공식 툴팁 귀속·거래 줄에 '거래 불가'가 있으면 아니고, '거래 제한 아이템 레벨' 줄이 있으면 가능(거래 가능한 장신구에만 있는 줄,
   * 계산 검증 10-25). 둘 다 없으면 모른다(null).
   */
  static Boolean tradeable(CharacterLoadout.Equipment item) {
    if (item.detail() == null) return null;
    boolean level = false;
    for (var line : item.detail().tradeLines()) {
      if (line == null) continue;
      if (line.replace(" ", "").contains("거래불가")) return false;
      if (TRADE_ITEM_LEVEL.matcher(line).find()) level = true;
    }
    return level ? Boolean.TRUE : null;
  }

  private static Instant earlier(Instant a, Instant b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.isBefore(b) ? a : b;
  }
}
