package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 장비 요약(equipment-summary-v1, #146). armory를 받을 때마다 이미 해석한 도메인(프로필·CharacterLoadout)에서 계산 검증에 필요한
 * 특성만 뽑는다. 원문은 쓰지 않는다. 필드는 계산 검증팀 요구([[장비 요약 테이블 필드 요구]])를 따르고, 못 읽은 값은 null이다(추정 금지).
 *
 * <p>배열은 고정 순서(공식 슬롯·부위 순서, 이름순)로 둔다. 같은 장비를 다시 관측하면 같은 값이 되어 변경 감지 해시가 바뀌지 않는다.
 */
public record EquipmentSummary(
    BigDecimal combatPower,
    BigDecimal itemLevel,
    Integer characterLevel,
    String className,
    String arkPassiveTitle,
    Features features) {
  public static final String SCHEMA_VERSION = "equipment-summary-v1";

  public record Features(
      Attack attack,
      Stats stats,
      ArkPassive arkPassive,
      List<Engraving> engravings,
      List<Gem> gems,
      BigDecimal gemBaseAttackPct,
      Weapon weapon,
      List<Armor> armor,
      List<Accessory> accessories,
      List<String> braceletLines,
      BigDecimal stoneLevelBonusBaseAttackPct,
      ArkGrid arkGrid,
      Cards cards,
      Long orbParadisePower,
      List<Skill> skills) {}

  public record Attack(Integer total, Integer base) {}

  public record Stats(
      Integer crit,
      Integer spec,
      Integer swift,
      Integer domination,
      Integer endurance,
      Integer expertise) {}

  public record ArkPassive(Trees points, Trees karmaRank, Trees karmaLevel, List<Node> nodes) {}

  public record Trees(Integer evolution, Integer enlightenment, Integer leap) {}

  public record Node(String tree, Integer tier, String name, Integer level) {}

  public record Engraving(String name, String grade, Integer level, Integer stone) {}

  /** mode: DAMAGE(피해 증가)·COOLDOWN(재사용 대기시간 감소). 보석 효과에서 못 찾으면 null. */
  public record Gem(String kind, Integer level, String mode) {}

  /** esther: 에스더 등급 무기만 값이 있다. 그 밖의 무기는 null이고 저장 JSON에서 빠져 기존 해시가 그대로다. */
  public record Weapon(
      Integer quality,
      BigDecimal addDamagePct,
      Integer weaponAttack,
      Integer refine,
      Esther esther) {}

  /** refineLevel: 이름 앞 "+N" 강화 단계, ellaStage: 툴팁 "엘라 부여 N 단계". 못 읽으면 null. */
  public record Esther(Integer refineLevel, Integer ellaStage) {}

  public record Armor(String slot, Integer refine, Integer quality, Integer itemLevel) {}

  /** polishLines: 연마 효과 "이름 값"(예: "추가 피해 +2.60%"). */
  public record Accessory(String slot, Integer quality, List<String> polishLines) {}

  /** effects: 그리드 효과 이름 → %. cores의 options는 현재 포인트로 달성한 구간 문장이다. */
  public record ArkGrid(Map<String, BigDecimal> effects, List<Core> cores) {}

  public record Core(
      String kind, String position, String grade, Integer point, List<String> options) {}

  /** set: 세트 이름, pieces: 가장 큰 세트 단계, awakening: 달성한 각성 합계 단계, elementPct: "…속성 피해 +x%" 합. */
  public record Cards(String set, Integer pieces, Integer awakening, BigDecimal elementPct) {}

  public record Skill(String name, String attackType) {}

  private static final Pattern NUMBER = Pattern.compile("\\d+");
  private static final Pattern BASE_ATTACK = Pattern.compile("기본 공격력은 (\\d+)");
  private static final Pattern KARMA = Pattern.compile("(\\d+)랭크 (\\d+)레벨");
  private static final Pattern NODE = Pattern.compile("^(\\S+) (\\d+)티어 (.+?) Lv\\.(\\d+)$");
  private static final Pattern GEM_KIND = Pattern.compile("(겁화|작열|광휘|멸화|홍염)의 보석");
  private static final Pattern GEM_BASE_ATTACK = Pattern.compile("기본 공격력 총합 : ([\\d.]+)%");
  private static final Pattern WEAPON_ATTACK = Pattern.compile("^무기 공격력 \\+(\\d+)$");
  private static final Pattern ADD_DAMAGE = Pattern.compile("^추가 피해 \\+([\\d.]+)%$");
  private static final Pattern STONE_BONUS = Pattern.compile("\\[레벨 보너스\\] 기본 공격력 \\+([\\d.]+)%");
  private static final Pattern ELLA = Pattern.compile("엘라 부여\\s*(\\d+)\\s*단계");
  private static final Pattern ORB = Pattern.compile("최대 낙원력 : (\\d+)");
  private static final Pattern CORE = Pattern.compile("^(질서|혼돈)의 (해|달|별) 코어");
  private static final Pattern CARD_SET =
      Pattern.compile("^(.+?)\\s+(\\d+)세트(?:\\s*\\((\\d+)각성합계\\))?$");
  private static final Pattern ELEMENT = Pattern.compile("^[성암뇌토수화]속성 피해 \\+([\\d.]+)%$");
  private static final List<String> ARMOR = List.of("투구", "상의", "하의", "장갑", "어깨");
  private static final List<String> ACCESSORY = List.of("목걸이", "귀걸이", "반지");

  public static EquipmentSummary from(CharacterProfile profile, CharacterLoadout loadout) {
    var passive = loadout.arkPassive();
    return new EquipmentSummary(
        profile.combatPower(),
        profile.itemLevel(),
        profile.characterLevel(),
        profile.className(),
        passive == null ? null : passive.title(),
        new Features(
            attack(profile),
            stats(profile),
            arkPassive(passive),
            engravings(loadout.engravings()),
            gems(loadout.gems()),
            loadout.gems() == null || loadout.gems().effects() == null
                ? null
                : decimal(GEM_BASE_ATTACK, loadout.gems().effects().description()),
            weapon(loadout.equipment()),
            armor(loadout.equipment()),
            accessories(loadout.equipment()),
            bracelet(loadout.equipment()),
            stoneBonus(loadout.equipment()),
            arkGrid(loadout.arkGrid()),
            cards(loadout.cards()),
            orb(loadout.equipment()),
            skills(loadout.skills())));
  }

  private static Attack attack(CharacterProfile profile) {
    var stat = stat(profile, "공격력");
    if (stat == null) return null;
    Integer base = null;
    for (var line : stat.tooltip()) {
      var matcher = BASE_ATTACK.matcher(line);
      if (matcher.find()) base = Integer.valueOf(matcher.group(1));
    }
    return new Attack(integer(stat.value()), base);
  }

  private static Stats stats(CharacterProfile profile) {
    return new Stats(
        statValue(profile, "치명"),
        statValue(profile, "특화"),
        statValue(profile, "신속"),
        statValue(profile, "제압"),
        statValue(profile, "인내"),
        statValue(profile, "숙련"));
  }

  private static ArkPassive arkPassive(CharacterLoadout.ArkPassive passive) {
    if (passive == null) return null;
    Integer[] points = new Integer[3];
    Integer[] ranks = new Integer[3];
    Integer[] levels = new Integer[3];
    for (var point : passive.points()) {
      if (point == null) continue;
      int index = tree(point.name());
      if (index < 0) continue;
      points[index] = point.value();
      if (point.description() != null) {
        var karma = KARMA.matcher(point.description());
        if (karma.find()) {
          ranks[index] = Integer.valueOf(karma.group(1));
          levels[index] = Integer.valueOf(karma.group(2));
        }
      }
    }
    var nodes = new ArrayList<Node>();
    for (var effect : passive.effects()) {
      if (effect == null || effect.description() == null) continue;
      var matcher = NODE.matcher(effect.description().strip());
      if (matcher.find())
        nodes.add(
            new Node(
                matcher.group(1),
                Integer.valueOf(matcher.group(2)),
                matcher.group(3),
                Integer.valueOf(matcher.group(4))));
    }
    nodes.sort(
        Comparator.comparing(Node::tree)
            .thenComparing(Node::tier)
            .thenComparing(Node::name)
            .thenComparing(Node::level));
    return new ArkPassive(trees(points), trees(ranks), trees(levels), List.copyOf(nodes));
  }

  private static Trees trees(Integer[] values) {
    return new Trees(values[0], values[1], values[2]);
  }

  private static int tree(String name) {
    if (name == null) return -1;
    return switch (name) {
      case "진화" -> 0;
      case "깨달음" -> 1;
      case "도약" -> 2;
      default -> -1;
    };
  }

  private static List<Engraving> engravings(CharacterLoadout.Engravings engravings) {
    if (engravings == null) return null;
    return engravings.arkPassiveEffects().stream()
        .filter(Objects::nonNull)
        .map(
            engraving ->
                new Engraving(
                    engraving.name(),
                    engraving.grade(),
                    engraving.level(),
                    engraving.abilityStoneLevel() == null ? 0 : engraving.abilityStoneLevel()))
        .sorted(Comparator.comparing(Engraving::name, Comparator.nullsLast(String::compareTo)))
        .toList();
  }

  private static List<Gem> gems(CharacterLoadout.Gems gems) {
    if (gems == null) return null;
    var skills =
        gems.effects() == null ? List.<CharacterLoadout.GemSkill>of() : gems.effects().skills();
    return gems.items().stream()
        .filter(gem -> gem != null && gem.name() != null)
        .sorted(
            Comparator.comparing(
                CharacterLoadout.Gem::slot, Comparator.nullsLast(Integer::compare)))
        .map(gem -> new Gem(match(GEM_KIND, gem.name()), gem.level(), mode(gem, skills)))
        .toList();
  }

  private static String mode(CharacterLoadout.Gem gem, List<CharacterLoadout.GemSkill> skills) {
    for (var skill : skills) {
      if (skill == null || !Objects.equals(skill.gemSlot(), gem.slot())) continue;
      for (var line : skill.description()) {
        if (line == null) continue;
        if (line.contains("재사용 대기시간")) return "COOLDOWN";
        if (line.contains("피해")) return "DAMAGE";
      }
    }
    return null;
  }

  private static Weapon weapon(List<CharacterLoadout.Equipment> equipment) {
    var weapon = first(equipment, "무기");
    if (weapon == null || weapon.detail() == null) return null;
    var detail = weapon.detail();
    BigDecimal addDamage = null;
    Integer weaponAttack = null;
    for (var line : lines(detail)) {
      if (addDamage == null) addDamage = decimal(ADD_DAMAGE, line);
      if (weaponAttack == null) {
        var value = match(WEAPON_ATTACK, line);
        if (value != null) weaponAttack = Integer.valueOf(value);
      }
    }
    return new Weapon(
        detail.quality(), addDamage, weaponAttack, detail.refineLevel(), esther(weapon));
  }

  /** 에스더 무기(공식 등급 "에스더"): 강화는 이름 앞 "+N"(재련 단계와 같은 자리), 엘라 부여는 효과 줄에서 읽는다. */
  private static Esther esther(CharacterLoadout.Equipment weapon) {
    if (!"에스더".equals(weapon.grade())) return null;
    Integer ella = null;
    for (var line : lines(weapon.detail())) {
      var value = match(ELLA, line);
      if (value != null) {
        ella = Integer.valueOf(value);
        break;
      }
    }
    return new Esther(weapon.detail().refineLevel(), ella);
  }

  private static List<Armor> armor(List<CharacterLoadout.Equipment> equipment) {
    var result = new ArrayList<Armor>();
    for (var item : equipment) {
      if (item == null || !ARMOR.contains(item.type())) continue;
      var detail = item.detail();
      result.add(
          new Armor(
              item.type(),
              detail == null ? null : detail.refineLevel(),
              detail == null ? null : detail.quality(),
              detail == null ? null : detail.itemLevel()));
    }
    result.sort(Comparator.comparingInt(armor -> ARMOR.indexOf(armor.slot())));
    return List.copyOf(result);
  }

  private static List<Accessory> accessories(List<CharacterLoadout.Equipment> equipment) {
    var result = new ArrayList<Accessory>();
    for (var item : equipment) {
      if (item == null || !ACCESSORY.contains(item.type())) continue;
      var detail = item.detail();
      var lines =
          detail == null
              ? List.<String>of()
              : detail.polishEffects().stream()
                  .filter(effect -> effect != null && effect.name() != null)
                  .map(
                      effect ->
                          effect.value() == null
                              ? effect.name()
                              : effect.name() + " " + effect.value())
                  .toList();
      result.add(new Accessory(item.type(), detail == null ? null : detail.quality(), lines));
    }
    // 같은 부위(귀걸이·반지 두 개)는 연마 문장으로 정렬해 공식 순서가 바뀌어도 같게 둔다.
    result.sort(
        Comparator.comparingInt((Accessory accessory) -> ACCESSORY.indexOf(accessory.slot()))
            .thenComparing(accessory -> String.join("|", accessory.polishLines()))
            .thenComparing(accessory -> accessory.quality() == null ? -1 : accessory.quality()));
    return List.copyOf(result);
  }

  private static List<String> bracelet(List<CharacterLoadout.Equipment> equipment) {
    var bracelet = first(equipment, "팔찌");
    if (bracelet == null || bracelet.detail() == null) return null;
    var lines = new ArrayList<String>();
    for (var effect : bracelet.detail().braceletEffects()) {
      if (effect == null || effect.text() == null) continue;
      for (var line : effect.text().split("\n")) if (!line.isBlank()) lines.add(line.strip());
    }
    return List.copyOf(lines);
  }

  private static BigDecimal stoneBonus(List<CharacterLoadout.Equipment> equipment) {
    var stone = first(equipment, "어빌리티 스톤");
    if (stone == null || stone.detail() == null) return null;
    for (var line : lines(stone.detail())) {
      var value = decimal(STONE_BONUS, line);
      if (value != null) return value;
    }
    return null;
  }

  private static Long orb(List<CharacterLoadout.Equipment> equipment) {
    var orb = first(equipment, "보주");
    if (orb == null || orb.detail() == null) return null;
    for (var line : lines(orb.detail())) {
      var value = match(ORB, line);
      if (value != null) return Long.valueOf(value);
    }
    return null;
  }

  private static ArkGrid arkGrid(CharacterLoadout.ArkGrid grid) {
    if (grid == null) return null;
    var effects = new TreeMap<String, BigDecimal>();
    for (var effect : grid.effects())
      if (effect != null && effect.name() != null && effect.percent() != null)
        effects.put(effect.name(), plain(effect.percent()));
    var cores = new ArrayList<Core>();
    for (var slot : grid.slots()) {
      if (slot == null || slot.name() == null) continue;
      var matcher = CORE.matcher(slot.name());
      String kind = matcher.find() ? matcher.group(1) : null;
      String position = kind == null ? null : matcher.group(2);
      var options = new ArrayList<String>();
      if (slot.core() != null && slot.point() != null)
        for (var option : slot.core().options())
          if (option != null && option.threshold() <= slot.point() && option.text() != null)
            options.add(option.text());
      cores.add(new Core(kind, position, slot.grade(), slot.point(), List.copyOf(options)));
    }
    return new ArkGrid(effects, List.copyOf(cores));
  }

  private static Cards cards(CharacterLoadout.Cards cards) {
    if (cards == null) return null;
    String set = null;
    int pieces = 0;
    int awakening = 0;
    BigDecimal element = BigDecimal.ZERO;
    for (var group : cards.effects()) {
      if (group == null) continue;
      for (var effect : group.items()) {
        if (effect == null || effect.name() == null) continue;
        var matcher = CARD_SET.matcher(effect.name().strip());
        if (matcher.find()) {
          int effectPieces = Integer.parseInt(matcher.group(2));
          int effectAwakening = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
          if (effectPieces > pieces) {
            set = matcher.group(1);
            pieces = effectPieces;
            awakening = effectAwakening;
          } else if (effectPieces == pieces && Objects.equals(set, matcher.group(1)))
            awakening = Math.max(awakening, effectAwakening);
        }
        if (effect.description() != null) {
          var value = decimal(ELEMENT, effect.description().strip());
          if (value != null) element = element.add(value);
        }
      }
    }
    return new Cards(
        set, set == null ? null : pieces, set == null ? null : awakening, plain(element));
  }

  /** 레벨을 올린(2 이상) 스킬만 이름순으로 둔다. */
  private static List<Skill> skills(List<CharacterLoadout.Skill> skills) {
    if (skills == null) return null;
    return skills.stream()
        .filter(skill -> skill != null && skill.name() != null && skill.level() != null)
        .filter(skill -> skill.level() > 1)
        .map(
            skill ->
                new Skill(
                    skill.name(), skill.attackType() == null ? null : skill.attackType().name()))
        .sorted(Comparator.comparing(Skill::name))
        .toList();
  }

  private static CharacterLoadout.Equipment first(
      List<CharacterLoadout.Equipment> equipment, String type) {
    if (equipment == null) return null;
    for (var item : equipment) if (item != null && type.equals(item.type())) return item;
    return null;
  }

  private static List<String> lines(CharacterLoadout.EquipmentDetail detail) {
    var lines = new ArrayList<String>();
    for (var group : detail.effects()) if (group != null) lines.addAll(group.lines());
    return lines;
  }

  private static CharacterProfile.Stat stat(CharacterProfile profile, String type) {
    for (var stat : profile.stats()) if (stat != null && type.equals(stat.type())) return stat;
    return null;
  }

  private static Integer statValue(CharacterProfile profile, String type) {
    var stat = stat(profile, type);
    return stat == null ? null : integer(stat.value());
  }

  private static Integer integer(String value) {
    if (value == null) return null;
    var matcher = NUMBER.matcher(value.replace(",", ""));
    return matcher.find() ? Integer.valueOf(matcher.group()) : null;
  }

  private static String match(Pattern pattern, String text) {
    if (text == null) return null;
    Matcher matcher = pattern.matcher(text.strip());
    return matcher.find() ? matcher.group(1) : null;
  }

  private static BigDecimal decimal(Pattern pattern, String text) {
    var value = match(pattern, text);
    return value == null ? null : plain(new BigDecimal(value));
  }

  /** 끝의 0을 지우되 지수 표기(3E+1)가 되지 않게 소수 자릿수를 0 이상으로 둔다. 같은 값은 같은 직렬화가 된다. */
  static BigDecimal plain(BigDecimal value) {
    var stripped = value.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
  }
}
