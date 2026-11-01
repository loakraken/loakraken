package com.loakraken.market.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 경매장 시세 표(auction-board-v1). 비교 기준의 보석·장신구 탭 구성을 따른다.
 *
 * <p>T4 연마 효과의 하·중·상 값은 공식 `/auctions/options`의 연마 효과(EtcOptions 7) 값 목록 중 T4 값이다. 2026-10-09 실검색으로
 * 고대 목걸이 적주피·추피 상상 4,877,777G, 하상 11,000G, 중중 6,000G가 비교 기준값과 일치함을 확인했다. 공식 값은 100배 정수다(2.00% →
 * 200).
 */
public final class AuctionCatalog {
  public static final String RULE_VERSION = "auction-board-v1";
  public static final int GEM_CATEGORY = 210000;
  public static final int POLISH_OPTION = 7;

  /** 공식 `/auctions/options` 장신구 기본 효과(1) 중 힘·민첩·지능(11). 2026-10-12 확인(N13 0단계). */
  public static final int BASE_EFFECT_OPTION = 1;

  public static final int MAIN_STAT_SECOND = 11;

  /** 공식 `/auctions/options` 아크 패시브(8) 중 깨달음(1). 목걸이·귀걸이·반지에 적용. 2026-10-12 확인(N13 0단계). */
  public static final int ARK_PASSIVE_OPTION = 8;

  public static final int ENLIGHTENMENT_SECOND = 1;
  public static final int TIER = 4;
  public static final List<String> GEM_KINDS = List.of("겁화", "작열");
  public static final int MAX_GEM_LEVEL = 10;

  private AuctionCatalog() {}

  public enum Grade {
    ANCIENT("고대"),
    RELIC("유물");

    private final String official;

    Grade(String official) {
      this.official = official;
    }

    public String official() {
      return official;
    }
  }

  public enum Role {
    DEALER,
    SUPPORT
  }

  public enum Level {
    LOW("하"),
    MID("중"),
    HIGH("상");

    private final String label;

    Level(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 연마 효과. low·mid·high는 공식 값(×100 정수)이다. */
  public enum PolishOption {
    ENEMY_DAMAGE(42, "적에게 주는 피해 증가", "적주피", 55, 120, 200),
    ADDITIONAL_DAMAGE(41, "추가 피해", "추피", 70, 160, 260),
    WEAPON_ATTACK_PERCENT(46, "무기 공격력 %", "무공%", 80, 180, 300),
    ATTACK_PERCENT(45, "공격력 %", "공%", 40, 95, 155),
    CRIT_DAMAGE(50, "치명타 피해", "치피", 110, 240, 400),
    CRIT_RATE(49, "치명타 적중률", "치적", 40, 95, 155),
    BRAND(44, "낙인력", "낙인력", 215, 480, 800),
    IDENTITY_GAUGE(43, "세레나데, 신앙, 조화 게이지 획득량 증가", "아덴", 160, 360, 600),
    ALLY_DAMAGE(52, "아군 피해량 강화 효과", "아피강", 200, 450, 750),
    ALLY_ATTACK(51, "아군 공격력 강화 효과", "아공강", 135, 300, 500);

    private final int code;
    private final String officialName;
    private final String shortName;
    private final int low;
    private final int mid;
    private final int high;

    PolishOption(int code, String officialName, String shortName, int low, int mid, int high) {
      this.code = code;
      this.officialName = officialName;
      this.shortName = shortName;
      this.low = low;
      this.mid = mid;
      this.high = high;
    }

    public int code() {
      return code;
    }

    public String officialName() {
      return officialName;
    }

    public String shortName() {
      return shortName;
    }

    public int value(Level level) {
      return switch (level) {
        case LOW -> low;
        case MID -> mid;
        case HIGH -> high;
      };
    }

    /** 매물 옵션 값(예: 2.0)이 T4 하·중·상 중 어디인지. 아니면 empty. */
    public Optional<Level> levelOf(BigDecimal displayValue) {
      if (displayValue == null) return Optional.empty();
      int raw = displayValue.movePointRight(2).intValue();
      for (var level : Level.values()) if (value(level) == raw) return Optional.of(level);
      return Optional.empty();
    }

    public static Optional<PolishOption> byOfficialName(String name) {
      for (var option : values()) if (option.officialName.equals(name)) return Optional.of(option);
      return Optional.empty();
    }
  }

  public enum Slot {
    NECKLACE(200010, "목걸이"),
    EARRING(200020, "귀걸이"),
    RING(200030, "반지");

    private final int categoryCode;
    private final String label;

    Slot(int categoryCode, String label) {
      this.categoryCode = categoryCode;
      this.label = label;
    }

    public int categoryCode() {
      return categoryCode;
    }

    public String label() {
      return label;
    }
  }

  /** 부위별 핵심 연마 효과. 서포터 귀걸이는 무공% 하나다(비교 기준값과 같음). */
  public static List<PolishOption> options(Role role, Slot slot) {
    return switch (role) {
      case DEALER ->
          switch (slot) {
            case NECKLACE -> List.of(PolishOption.ENEMY_DAMAGE, PolishOption.ADDITIONAL_DAMAGE);
            case EARRING ->
                List.of(PolishOption.WEAPON_ATTACK_PERCENT, PolishOption.ATTACK_PERCENT);
            case RING -> List.of(PolishOption.CRIT_DAMAGE, PolishOption.CRIT_RATE);
          };
      case SUPPORT ->
          switch (slot) {
            case NECKLACE -> List.of(PolishOption.BRAND, PolishOption.IDENTITY_GAUGE);
            case EARRING -> List.of(PolishOption.WEAPON_ATTACK_PERCENT);
            case RING -> List.of(PolishOption.ALLY_DAMAGE, PolishOption.ALLY_ATTACK);
          };
    };
  }

  /**
   * 연마 효과가 그 부위에 붙을 수 있는지. 공식 `/auctions/options` 연마 효과의 Categorys(2026-10-12 확인): 목걸이 41~44, 귀걸이
   * 45~48, 반지 49~52.
   */
  public static boolean fits(Slot slot, PolishOption option) {
    int code = option.code();
    return switch (slot) {
      case NECKLACE -> code >= 41 && code <= 44;
      case EARRING -> code >= 45 && code <= 48;
      case RING -> code >= 49 && code <= 52;
    };
  }

  /**
   * 표의 조합 순서. 옵션 2개면 상상·상중·중상·중중·상하·하상(중하·하중·하하 제외 — 칸마다 공식 검색 1번이라 찾는 사람이 적은 조합은 표에 두지 않는다),
   * 1개면 상·중·하. 표에 없는 조합도 매물 목록(levels)으로는 검색할 수 있다.
   */
  public static List<List<Level>> combinations(int optionCount) {
    if (optionCount == 1)
      return List.of(List.of(Level.HIGH), List.of(Level.MID), List.of(Level.LOW));
    var h = Level.HIGH;
    var m = Level.MID;
    var l = Level.LOW;
    return List.of(
        List.of(h, h),
        List.of(h, m),
        List.of(m, h),
        List.of(m, m),
        List.of(h, l),
        List.of(l, h));
  }

  public static String gemName(String kind, int level) {
    return level + "레벨 " + kind + "의 보석";
  }

  public static String label(List<Level> levels) {
    var label = new StringBuilder();
    levels.forEach(level -> label.append(level.label()));
    return label.toString();
  }

  public static List<String> optionLabels(List<PolishOption> options, List<Level> levels) {
    var labels = new ArrayList<String>();
    for (int i = 0; i < options.size(); i++)
      labels.add(options.get(i).shortName() + " " + levels.get(i).label());
    return labels;
  }
}
