package com.loakraken.market.domain;

/**
 * LOAKraken 시세 보드 탭. 공식 거래소 분류 코드에 대응하며, 공식 options가 바뀌어도 탭 이름은 유지한다.
 *
 * <p>2026-10-08 공식 options 기준. 51000(기타 재료)은 품목이 없어 제외했다. 보석·장신구는 경매장 API라 거래소 탭이 아니다.
 *
 * <p>2026-11-08 아바타(20000)·장비 상자(10100) 추가: 목표 사이트 실관찰로 확인한 분류이고 PM이 공식 호출 예산을 승인했다(요청 때만 부르고 배경 작업
 * 없음). 값이 천천히 바뀌는 분류라 가격·스캔 캐시를 길게 둔다({@link #slowMoving()}).
 */
public enum MarketTab {
  REFINING("재련 재료", 50010, 4),
  REFINING_EXTRA("재련 추가 재료", 50020, 4),
  ARK_GRID("아크 그리드 재료", 230000, null),
  WEAPON_EVOLUTION("무기 진화 재료", 51100, null),
  ENGRAVING_BOOK("각인서", 40000, null),
  LIFE("생활", 90000, null),
  BATTLE_ITEM("전투 용품", 60000, null),
  FOOD("요리", 70000, null),
  AVATAR("아바타", 20000, null, true),
  EQUIPMENT_BOX("장비 상자", 10100, null, true);

  private final String label;
  private final int categoryCode;
  private final Integer defaultTier;
  private final boolean slowMoving;

  MarketTab(String label, int categoryCode, Integer defaultTier) {
    this(label, categoryCode, defaultTier, false);
  }

  MarketTab(String label, int categoryCode, Integer defaultTier, boolean slowMoving) {
    this.label = label;
    this.categoryCode = categoryCode;
    this.defaultTier = defaultTier;
    this.slowMoving = slowMoving;
  }

  /** 값이 천천히 바뀌는 분류(가격·스캔 캐시를 길게 둠). */
  public boolean slowMoving() {
    return slowMoving;
  }

  /** 공식 분류 코드가 천천히 바뀌는 탭의 것인가. */
  public static boolean slowCategory(int categoryCode) {
    for (var tab : values()) if (tab.categoryCode == categoryCode) return tab.slowMoving;
    return false;
  }

  public String label() {
    return label;
  }

  public int categoryCode() {
    return categoryCode;
  }

  /** 기본 티어. null이면 티어 조건 없이 조회한다. */
  public Integer defaultTier() {
    return defaultTier;
  }
}
