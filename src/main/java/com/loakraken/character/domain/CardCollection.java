package com.loakraken.character.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 원정대 카드 기록(F-6, card-record-v1).
 *
 * <p>공식 API에는 카드 도감이 없다. 외부 서비스처럼 원정대 캐릭터가 검색될 때 장착한 카드를 기록하고, 카드별 최고 각성으로 원정대가 보유한 카드를 추정한다. 카드와
 * 각성은 원정대가 공유하므로 기록은 실제 보유의 하한이다. 세트 구성원과 효과 문구는 공식 응답의 CardSlots에서 학습한다.
 */
public final class CardCollection {
  public static final String RULE_VERSION = "card-record-v1";
  public static final int MAX_EQUIPPED = 6;
  private static final Pattern EFFECT_NAME =
      Pattern.compile("^(.+?)\\s+(\\d+)세트(?:\\s*\\((\\d+)각성합계\\))?$");

  private CardCollection() {}

  public enum Element {
    LIGHT,
    DARK,
    LIGHTNING,
    EARTH,
    WATER,
    FIRE
  }

  /** 원정대 카드 기록의 속성 6칸. 순서는 성·암·뇌·토·수·화. */
  public static final List<ElementSet> DEALER_SETS =
      List.of(
          new ElementSet(Element.LIGHT, "세상을 구하는 빛"),
          new ElementSet(Element.DARK, "카제로스의 군단장"),
          new ElementSet(Element.LIGHTNING, "날랜 뇌전의 숨결"),
          new ElementSet(Element.EARTH, "굳센 대지의 숨결"),
          new ElementSet(Element.WATER, "거센 파도의 숨결"),
          new ElementSet(Element.FIRE, "힘찬 화염의 숨결"));

  public static final List<ElementSet> SUPPORTER_SETS =
      List.of(
          new ElementSet(Element.LIGHT, "남겨진 바람의 절벽"),
          new ElementSet(Element.DARK, "창의 달인"),
          new ElementSet(Element.LIGHTNING, "몰아치는 뇌전의 가호"),
          new ElementSet(Element.EARTH, "잠재우는 대지의 가호"),
          new ElementSet(Element.WATER, "노래하는 파도의 가호"),
          new ElementSet(Element.FIRE, "피어나는 화염의 가호"));

  public record ElementSet(Element element, String setName) {}

  /** 원정대에서 관측한 카드 한 장의 최고 기록. */
  public record OwnedCard(
      String name,
      String grade,
      String icon,
      int awakeCount,
      Integer awakeTotal,
      Instant observedAt,
      String recordedBy) {}

  /** 세트 효과 한 줄. awakening은 각성합계 조건이며 없으면 0이다. */
  public record SetEffect(String setName, int pieces, int awakening, String description) {}

  public record SetMember(String setName, String cardName) {}

  /** 장착 카드 한 벌에서 학습한 세트 구성원과 효과. */
  public record Learned(List<SetMember> members, List<SetEffect> effects) {}

  public record FormedEffect(int pieces, int awakening, String description, boolean active) {}

  public record FormedSet(
      String setName,
      int cardCount,
      int awakening,
      Integer activeAwakening,
      List<String> cards,
      List<FormedEffect> effects,
      Instant observedAt,
      String recordedBy) {}

  public record ElementRecord(Element element, String setName, FormedSet record) {}

  public record Result(
      List<ElementRecord> dealer,
      List<ElementRecord> supporter,
      List<OwnedCard> cards,
      List<FormedSet> sets) {}

  /** 장착 카드의 세트 효과 이름("세상을 구하는 빛 6세트 (30각성합계)")에서 세트명·장수·각성합계를 읽는다. */
  public static Learned learn(CharacterLoadout.Cards cards) {
    if (cards == null) return new Learned(List.of(), List.of());
    var slotNames = new HashMap<Integer, String>();
    for (var card : cards.items())
      if (card != null && card.slot() != null && card.name() != null && !card.name().isBlank())
        slotNames.put(card.slot(), card.name());
    var members = new LinkedHashSet<SetMember>();
    var effects = new LinkedHashSet<SetEffect>();
    for (var set : cards.effects()) {
      if (set == null) continue;
      String setName = null;
      for (var item : set.items()) {
        if (item == null || item.name() == null) continue;
        var matcher = EFFECT_NAME.matcher(item.name().trim());
        if (!matcher.matches()) continue;
        setName = matcher.group(1);
        int awakening = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        String description = item.description() == null ? "" : item.description().trim();
        effects.add(
            new SetEffect(setName, Integer.parseInt(matcher.group(2)), awakening, description));
      }
      if (setName == null) continue;
      for (var slot : set.cardSlots()) {
        var cardName = slotNames.get(slot);
        if (cardName != null) members.add(new SetMember(setName, cardName));
      }
    }
    return new Learned(List.copyOf(members), List.copyOf(effects));
  }

  /**
   * 원정대 카드로 구성할 수 있는 세트를 계산한다. 구성원 중 각성이 높은 카드부터 최대 6장을 쓴다. 효과는 장수와 각성합계 조건을 모두 채우면 활성이다. 기록 칸은 2세트
   * 이상일 때만 채운다.
   */
  public static Result compute(
      List<OwnedCard> owned, List<SetMember> members, List<SetEffect> effects) {
    var byName = new LinkedHashMap<String, OwnedCard>();
    for (var card : owned) byName.merge(card.name(), card, CardCollection::better);
    var setCards = new LinkedHashMap<String, List<OwnedCard>>();
    for (var member : members) {
      var card = byName.get(member.cardName());
      if (card != null)
        setCards.computeIfAbsent(member.setName(), key -> new ArrayList<>()).add(card);
    }
    var setEffects = new HashMap<String, List<SetEffect>>();
    for (var effect : effects)
      setEffects.computeIfAbsent(effect.setName(), key -> new ArrayList<>()).add(effect);

    var formed = new LinkedHashMap<String, FormedSet>();
    for (var entry : setCards.entrySet()) {
      var set =
          form(
              entry.getKey(), entry.getValue(), setEffects.getOrDefault(entry.getKey(), List.of()));
      if (set != null) formed.put(set.setName(), set);
    }
    var sets =
        formed.values().stream()
            .sorted(
                Comparator.comparingInt(FormedSet::awakening)
                    .thenComparingInt(FormedSet::cardCount)
                    .reversed()
                    .thenComparing(FormedSet::setName))
            .toList();
    var cards = byName.values().stream().sorted(Comparator.comparing(OwnedCard::name)).toList();
    return new Result(slots(DEALER_SETS, formed), slots(SUPPORTER_SETS, formed), cards, sets);
  }

  private static FormedSet form(String setName, List<OwnedCard> cards, List<SetEffect> effects) {
    var used =
        cards.stream()
            .distinct()
            .sorted(
                Comparator.comparingInt(OwnedCard::awakeCount)
                    .reversed()
                    .thenComparing(OwnedCard::name))
            .limit(MAX_EQUIPPED)
            .toList();
    int count = used.size();
    if (count < 2) return null;
    int awakening = used.stream().mapToInt(OwnedCard::awakeCount).sum();
    var formedEffects =
        effects.stream()
            .distinct()
            .sorted(
                Comparator.comparingInt(SetEffect::pieces).thenComparingInt(SetEffect::awakening))
            .map(
                effect ->
                    new FormedEffect(
                        effect.pieces(),
                        effect.awakening(),
                        effect.description(),
                        effect.pieces() <= count && effect.awakening() <= awakening))
            .toList();
    Integer activeAwakening =
        formedEffects.stream()
            .filter(effect -> effect.active() && effect.awakening() > 0)
            .map(FormedEffect::awakening)
            .max(Integer::compare)
            .orElse(null);
    var latest = used.stream().max(Comparator.comparing(OwnedCard::observedAt)).orElseThrow();
    return new FormedSet(
        setName,
        count,
        awakening,
        activeAwakening,
        used.stream().map(OwnedCard::name).toList(),
        formedEffects,
        latest.observedAt(),
        latest.recordedBy());
  }

  private static List<ElementRecord> slots(List<ElementSet> slots, Map<String, FormedSet> formed) {
    return slots.stream()
        .map(slot -> new ElementRecord(slot.element(), slot.setName(), formed.get(slot.setName())))
        .toList();
  }

  private static OwnedCard better(OwnedCard left, OwnedCard right) {
    if (left.awakeCount() != right.awakeCount())
      return left.awakeCount() > right.awakeCount() ? left : right;
    return left.observedAt().isAfter(right.observedAt()) ? left : right;
  }
}
