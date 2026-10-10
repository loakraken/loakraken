package com.loakraken;

/** 사용자에게 보이는 문구·OpenAPI 설명에 쓰면 안 되는 다른 사이트 이름(사용자 지시 2026-10-10 23:25, #261). */
public final class SiteNames {
  public static final String[] FORBIDDEN = {
    "일로아",
    "로아와",
    "클로아",
    "로펙",
    "로아랩",
    "lo4.app",
    "아이스펭",
    "icepeng",
    "인벤",
    "inven.co",
    "로아또",
    "돌파고",
    // 화면에 '목표 사이트' 같은 간접 표현도 쓰지 않는다(성장 도구 10-10 23:47).
    "목표 사이트",
    "두 사이트",
    "계산 사이트"
  };

  private SiteNames() {}
}
