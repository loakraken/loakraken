package com.loakraken.common.api;

/**
 * 저장·순위에 쓰는 효율 계산 규칙 버전(L38·L39). 캐릭터 모듈은 이 버전으로 계산해 저장하고, 통계 모듈은 이 버전 값만 순위에 넣는다. 계산 모델을 바꾸면 여기 값을
 * 올린다(랭킹은 다음 관측 때 다시 저장된 캐릭터부터 센다).
 */
public final class EfficiencyRuleVersions {
  /** 전투력 시뮬레이터(팔찌 효율 포함) 규칙 버전. */
  public static final String COMBAT_POWER_SIM = "combat-power-sim-v8";

  /** 아크 그리드 효율 규칙 버전. combat-power/ark-grid-coefficients.json의 ruleVersion과 같아야 한다(시작 때 확인). */
  public static final String ARK_GRID = "ark-grid-efficiency-v1";

  /** 팔찌 실 효율(크라켄 점수 엔진, 비교 기준 B의 정보 탭 팔찌 %) 규칙 버전. 캐릭터 응답 braceletEfficiency.real과 같은 값이다. */
  public static final String BRACELET_REAL = "bracelet-real-v1";

  /** 아크 그리드 젬 실 효율(#520, 비교 기준 B의 젬 실 효율) 규칙 버전. 캐릭터 응답 arkGridEfficiency.real과 같은 값이다. */
  public static final String ARK_GRID_REAL = "ark-grid-real-v1";

  private EfficiencyRuleVersions() {}
}
