package com.loakraken.character.domain;

/**
 * 보석을 거래소에 팔 수 없는가(귀속). 공식 보석 이름의 '(귀속)' 또는 공식 툴팁 거래 줄(Gem.tradeLines)의 '귀속됨'·'거래 불가' 가운데 하나라도 있으면
 * 귀속이다(#560). 이름에 표시가 없는 귀속 보석을 팔 수 있다고 보면 판매 기대가를 빼서 비용을 실제보다 적게 보여 준다. 거래 줄 예: '캐릭터 귀속됨'·'거래
 * 불가'(귀속), '거래가능'·'거래 3회 가능'(거래 가능).
 */
public final class GemTrade {
  /** 공백을 모두 뺀 거래 줄 전체가 이 꼴이면 귀속. '귀속 시 거래 불가' 같은 조건 문구는 귀속이 아니다(코드 리뷰 P2). */
  private static final java.util.regex.Pattern BOUND_LINE =
      java.util.regex.Pattern.compile("(캐릭터|원정대)?귀속됨|거래불가");

  private GemTrade() {}

  public static boolean bound(CharacterLoadout.Gem gem) {
    if (gem == null) return false;
    if (gem.name() != null && gem.name().contains("(귀속)")) return true;
    for (var line : gem.tradeLines()) {
      if (line == null) continue;
      // 탭·줄 바꿈 없는 공백(U+00A0)까지 뺀다.
      var compact = line.replaceAll("[\\s\\u00A0]+", "");
      if (BOUND_LINE.matcher(compact).matches()) return true;
    }
    return false;
  }
}
