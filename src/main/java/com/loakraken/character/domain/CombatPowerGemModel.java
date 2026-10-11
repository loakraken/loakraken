package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 전투력 시뮬레이터 보석 항목(B단계, #243). 근거는 볼트 「전투력 검증 준비」 7·8절과 리소스 combat-power/gem-coefficients.json.
 *
 * <p>전투력 비 = Π(1 + 보석 배수) × (1 + 기본 공격력 % 합). 기본 공격력 % 합은 공식 툴팁의 세 문구에서만 모은다: 보석 효과 "기본 공격력 n% 증가",
 * 어빌리티 스톤 "[레벨 보너스] 기본 공격력 +n%", 완갑 "추가 효과" "기본 공격력 +n%". 그 밖에서 기본 공격력 % 문구를 만나거나 보석 표기가 계수표와 다르면
 * 추정하지 않고 계산하지 않는다(계산 일치 원칙).
 */
public final class CombatPowerGemModel {
  private static final MathContext MATH = MathContext.DECIMAL64;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final Pattern GEM_KIND = Pattern.compile("(겁화|작열|광휘|멸화|홍염)의 보석");
  private static final Pattern GEM_OPTION = Pattern.compile("기본 공격력 ([\\d.]+)% 증가");
  private static final Pattern STONE_BONUS = Pattern.compile("\\[레벨 보너스\\] 기본 공격력 \\+([\\d.]+)%");
  private static final Pattern ARM_GUARD_BONUS = Pattern.compile("^기본 공격력 \\+([\\d.]+)%$");
  private static final Pattern ANY_BASE_ATTACK_PCT =
      Pattern.compile("기본 공격력[^\\d%]{0,8}[\\d.]+\\s*%");
  private static final String STONE = "어빌리티 스톤";
  private static final String ARM_GUARD = "완갑";

  /** 리소스 combat-power/gem-coefficients.json. */
  public record Coefficients(String ruleVersion, Map<String, String> source, List<Tier> tiers) {
    public Coefficients {
      source = source == null ? Map.of() : Map.copyOf(source);
      tiers = tiers == null ? List.of() : List.copyOf(tiers);
    }

    Tier tierOf(String kind) {
      for (var tier : tiers) if (tier.kinds().contains(kind)) return tier;
      return null;
    }
  }

  public record Tier(
      int tier, List<String> kinds, boolean supported, String note, List<Level> levels) {
    public Tier {
      kinds = kinds == null ? List.of() : List.copyOf(kinds);
      levels = levels == null ? List.of() : List.copyOf(levels);
    }

    Level level(int value) {
      for (var level : levels) if (level.level() == value) return level;
      return null;
    }
  }

  public record Level(int level, BigDecimal damagePct, BigDecimal baseAttackPct) {}

  /** 보석 한 칸의 현재 상태. */
  public record Slot(int slot, String name, Tier tier, int level) {}

  /**
   * 캐릭터 하나의 보석 모델. unsupportedReason이 있으면 보석 변화는 계산하지 않는다(그 이유를 basis에 쓴다). otherBaseAttackPct는 보석
   * 밖의 기본 공격력 %(스톤 레벨 보너스 + 완갑 추가 효과)다.
   */
  public record Model(
      Map<Integer, Slot> slots, BigDecimal otherBaseAttackPct, String unsupportedReason) {
    public Model {
      slots = slots == null ? Map.of() : java.util.Collections.unmodifiableMap(slots);
    }

    /** 칸별 레벨(levels)로 바꾼 상태의 전투력 비. 계산할 수 없는 칸이 있으면 null. */
    public BigDecimal ratio(Map<Integer, Integer> levels) {
      if (unsupportedReason != null) return null;
      var product = BigDecimal.ONE;
      var baseAttack = otherBaseAttackPct;
      for (var slot : slots.values()) {
        var level = slot.tier().level(levels.getOrDefault(slot.slot(), slot.level()));
        if (level == null || level.damagePct() == null || level.baseAttackPct() == null)
          return null;
        product = product.multiply(BigDecimal.ONE.add(level.damagePct().divide(HUNDRED)), MATH);
        baseAttack = baseAttack.add(level.baseAttackPct());
      }
      return product.multiply(BigDecimal.ONE.add(baseAttack.divide(HUNDRED)), MATH);
    }

    /** 칸별 레벨(levels, 없는 칸은 지금 레벨)로 바꾼 상태의 기본 공격력 %(백분율). 계산할 수 없으면 null(#341). */
    public BigDecimal baseAttackPct(Map<Integer, Integer> levels) {
      if (unsupportedReason != null) return null;
      var total = otherBaseAttackPct;
      for (var slot : slots.values()) {
        var level = slot.tier().level(levels.getOrDefault(slot.slot(), slot.level()));
        if (level == null || level.baseAttackPct() == null) return null;
        total = total.add(level.baseAttackPct());
      }
      return total;
    }

    /** 지금 세팅의 기본 공격력 %(보석 + 스톤 레벨 보너스 + 팔 보호구, 백분율). 계산할 수 없으면 null(재련 모델 #319가 같은 값을 쓴다). */
    public BigDecimal currentBaseAttackPct() {
      if (unsupportedReason != null) return null;
      var total = otherBaseAttackPct;
      for (var slot : slots.values()) {
        var level = slot.tier().level(slot.level());
        if (level == null || level.baseAttackPct() == null) return null;
        total = total.add(level.baseAttackPct());
      }
      return total;
    }

    /** 칸 slot을 level로 바꿀 수 있는지. 안 되면 이유 문장, 되면 null. */
    public String cannotSet(int slot, int level) {
      if (unsupportedReason != null) return unsupportedReason;
      var current = slots.get(slot);
      if (current == null) return null;
      var target = current.tier().level(level);
      if (target == null || target.baseAttackPct() == null)
        return level + "레벨 보석의 기본 공격력 수치를 아직 확인하지 않았습니다.";
      return null;
    }
  }

  private CombatPowerGemModel() {}

  public static Model of(Coefficients coefficients, CharacterLoadout loadout) {
    var slots = new LinkedHashMap<Integer, Slot>();
    var gems = loadout.gems();
    if (gems == null || gems.items().isEmpty())
      return new Model(slots, BigDecimal.ZERO, "장착한 보석이 없습니다.");
    var options = new LinkedHashMap<Integer, String>();
    if (gems.effects() != null)
      for (var skill : gems.effects().skills())
        if (skill != null && skill.gemSlot() != null) options.put(skill.gemSlot(), skill.option());
    for (var gem : gems.items()) {
      if (gem == null || gem.slot() == null || gem.level() == null) continue;
      var kind = match(GEM_KIND, gem.name());
      var tier = kind == null ? null : coefficients.tierOf(kind);
      if (tier == null)
        return new Model(slots, BigDecimal.ZERO, "보석 종류(" + gem.name() + ")를 알 수 없습니다.");
      if (!tier.supported())
        return new Model(slots, BigDecimal.ZERO, tier.tier() + "티어 보석 계수는 아직 확인하지 않았습니다.");
      var level = tier.level(gem.level());
      if (level == null || level.baseAttackPct() == null)
        return new Model(slots, BigDecimal.ZERO, gem.level() + "레벨 보석의 기본 공격력 수치를 아직 확인하지 않았습니다.");
      // 공식 표기와 계수표가 다르면 계수표를 믿지 않는다.
      var option = decimal(GEM_OPTION, options.get(gem.slot()));
      if (option == null || option.compareTo(level.baseAttackPct()) != 0)
        return new Model(
            slots,
            BigDecimal.ZERO,
            "보석 "
                + gem.slot()
                + "번의 기본 공격력 표기("
                + (option == null ? "없음" : option.toPlainString() + "%")
                + ")가 계수표("
                + level.baseAttackPct().toPlainString()
                + "%)와 달라 계산하지 않습니다.");
      slots.put(gem.slot(), new Slot(gem.slot(), gem.name(), tier, gem.level()));
    }
    var other = BigDecimal.ZERO;
    var unknown = new ArrayList<String>();
    for (var item : loadout.equipment()) {
      if (item == null || item.detail() == null) continue;
      for (var line : lines(item.detail())) {
        if (!ANY_BASE_ATTACK_PCT.matcher(line).find()) continue;
        var stone = STONE.equals(item.type()) ? decimal(STONE_BONUS, line) : null;
        var guard = ARM_GUARD.equals(item.type()) ? decimal(ARM_GUARD_BONUS, line.strip()) : null;
        if (stone != null) other = other.add(stone);
        else if (guard != null) other = other.add(guard);
        else unknown.add(line.strip());
      }
    }
    for (var text : otherTexts(loadout))
      if (ANY_BASE_ATTACK_PCT.matcher(text).find()) unknown.add(text.strip());
    if (!unknown.isEmpty())
      return new Model(
          slots, other, "확인하지 않은 기본 공격력 % 출처(「" + unknown.getFirst() + "」)가 있어 보석 변화는 계산하지 않습니다.");
    return new Model(slots, other, null);
  }

  /** 어빌리티 스톤 "[레벨 보너스] 기본 공격력 +n%"(공식 문구) 합. 없으면 0(#344). */
  public static BigDecimal stoneBonusPct(CharacterLoadout loadout) {
    var total = BigDecimal.ZERO;
    for (var item : loadout.equipment()) {
      if (item == null || item.detail() == null || !STONE.equals(item.type())) continue;
      for (var line : lines(item.detail())) {
        var value = decimal(STONE_BONUS, line);
        if (value != null) total = total.add(value);
      }
    }
    return total;
  }

  private static List<String> lines(CharacterLoadout.EquipmentDetail detail) {
    var lines = new ArrayList<String>();
    for (var group : detail.effects()) if (group != null) lines.addAll(group.lines());
    return lines;
  }

  /** 보석·장비 밖에서 기본 공격력 %가 붙을 수 있는 문구(아크 패시브·아크 그리드·각인). 6명 조사에서는 없었다. */
  private static List<String> otherTexts(CharacterLoadout loadout) {
    var texts = new ArrayList<String>();
    if (loadout.arkPassive() != null)
      for (var effect : loadout.arkPassive().effects()) {
        if (effect == null) continue;
        if (effect.description() != null) texts.add(effect.description());
        if (effect.effect() != null) texts.add(effect.effect());
      }
    if (loadout.arkGrid() != null) {
      for (var effect : loadout.arkGrid().effects())
        if (effect != null && effect.name() != null)
          texts.add(effect.name() + " " + (effect.value() == null ? "" : effect.value()));
      for (var slot : loadout.arkGrid().slots())
        if (slot != null && slot.core() != null)
          for (var option : slot.core().options()) if (option != null) texts.add(option.text());
    }
    if (loadout.engravings() != null)
      for (var engraving : loadout.engravings().arkPassiveEffects())
        if (engraving != null && engraving.description() != null)
          texts.add(engraving.description());
    return texts;
  }

  private static String match(Pattern pattern, String text) {
    if (text == null) return null;
    var matcher = pattern.matcher(text);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static BigDecimal decimal(Pattern pattern, String text) {
    var value = match(pattern, text);
    return value == null ? null : new BigDecimal(value);
  }
}
