package com.loakraken.receipt.domain;

import com.loakraken.character.domain.CharacterLoadout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 보석(receipt-v1). 이름에 "(귀속)"이 있거나 공식 툴팁 거래 줄이 귀속이면 귀속이라 개수만 센다. 거래 가능 보석은 경매장 시세 표의 최저 즉시 구매가로 환산한다. 표에 없는 종류(광휘 등)는
 * NO_PRICE, 칸이 아직 비면 PENDING이고 금액에 넣지 않는다.
 */
public final class GemReceipt {
  private static final Pattern GEM = Pattern.compile("(\\d+)레벨 (\\S+)의 보석");

  private GemReceipt() {}

  public enum Status {
    PRICED,
    NO_PRICE,
    PENDING
  }

  public record Key(String kind, int level) {}

  /** 시세: 없으면(표에 없는 종류) 맵에 없음, 칸이 비면 값이 null. */
  public record Price(BigDecimal unitPrice, boolean pending) {}

  public record Item(
      String kind, int level, int count, BigDecimal unitPrice, BigDecimal gold, Status status) {}

  public record Result(
      List<Item> items, int tradable, int priced, int bound, int unpriced, BigDecimal gold) {}

  public static List<Key> tradableKeys(List<CharacterLoadout.Gems> gems) {
    var keys = new ArrayList<Key>();
    for (var group : gems)
      for (var gem : group.items()) {
        var key = key(gem);
        if (key != null && !bound(gem) && !keys.contains(key)) keys.add(key);
      }
    return keys;
  }

  public static Result calculate(List<CharacterLoadout.Gems> gems, Map<Key, Price> prices) {
    var counts = new LinkedHashMap<Key, Integer>();
    int bound = 0;
    for (var group : gems)
      for (var gem : group.items()) {
        if (gem == null || gem.name() == null) continue;
        if (bound(gem)) {
          bound++;
          continue;
        }
        var key = key(gem);
        if (key != null) counts.merge(key, 1, Integer::sum);
      }
    var items = new ArrayList<Item>();
    int tradable = 0;
    int priced = 0;
    int unpriced = 0;
    var gold = BigDecimal.ZERO;
    for (var entry : counts.entrySet()) {
      var key = entry.getKey();
      int count = entry.getValue();
      tradable += count;
      var price = prices.get(key);
      if (price == null) {
        unpriced += count;
        items.add(new Item(key.kind(), key.level(), count, null, null, Status.NO_PRICE));
      } else if (price.pending() || price.unitPrice() == null) {
        unpriced += count;
        items.add(new Item(key.kind(), key.level(), count, null, null, Status.PENDING));
      } else {
        priced += count;
        var value = price.unitPrice().multiply(BigDecimal.valueOf(count));
        gold = gold.add(value);
        items.add(
            new Item(key.kind(), key.level(), count, price.unitPrice(), value, Status.PRICED));
      }
    }
    items.sort(
        Comparator.comparing(Item::kind).thenComparing(Item::level, Comparator.reverseOrder()));
    return new Result(List.copyOf(items), tradable, priced, bound, unpriced, gold);
  }

  /** 이름의 "(귀속)" 또는 공식 툴팁 거래 줄(스펙업과 같은 판정, #560). */
  static boolean bound(CharacterLoadout.Gem gem) {
    return com.loakraken.character.domain.GemTrade.bound(gem);
  }

  static Key key(CharacterLoadout.Gem gem) {
    if (gem == null || gem.name() == null) return null;
    var matcher = GEM.matcher(gem.name());
    if (!matcher.find()) return null;
    int level = gem.level() != null ? gem.level() : Integer.parseInt(matcher.group(1));
    return new Key(matcher.group(2), level);
  }
}
