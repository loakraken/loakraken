package com.loakraken.character.application;

import java.math.BigDecimal;

/**
 * 팔찌 실 효율. 원본 정보 탭의 팔찌 % 함수(크라켄 점수 엔진, 비교 기준 B)로 낸 값이다. 딜러는 팔찌 총배율로 낸 값이고 원본이 이미 % 둘째 자리
 * 내림이다. 서포터는 팔찌가 있는 점수 ÷ 팔찌를 뺀 점수로 낸 원본 값 그대로다(반올림하지 않음). 기존 '전투력 기여'(공식 전투력 계수)와 정의가 다르다.
 *
 * <p>status가 UNSUPPORTED면 percent는 null이다. role은 판정하지 못했으면 null이다.
 */
public record BraceletRealEfficiency(
    Status status, String reasonCode, String reason, String role, BigDecimal percent, String ruleVersion) {
  public static final String RULE_VERSION = "bracelet-real-v1";

  public enum Status {
    OK,
    UNSUPPORTED
  }

  public static BraceletRealEfficiency unsupported(String reasonCode, String reason, String role) {
    return new BraceletRealEfficiency(Status.UNSUPPORTED, reasonCode, reason, role, null, RULE_VERSION);
  }
}
