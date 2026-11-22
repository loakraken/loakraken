package com.loakraken.refining.domain;

import java.util.List;
import java.util.Map;

/**
 * 상급 재련 규칙(refining/t4-advanced-refining-rules.json, 계산 검증 명세 2026-11-22). 확률은 모든 구간·부위에 같고, 1회 재료는
 * 부위·구간(0·10·20·30)마다 다르다. 성장 지원(1~20단계, 구간 0·10)은 재료·숨결 ×0.3, 파편·실링 ×0.1, 골드 ×0.5를 각각 올림하고, 책은 줄이지
 * 않는다.
 *
 * @param singleSource 출처가 하나뿐인 항목(화면·응답에 표시)
 */
public record AdvancedRefineRules(
    String ruleVersion,
    Map<String, Object> source,
    AdvancedRefineChain.Odds odds,
    GrowthSupport growthSupport,
    Map<String, List<Band>> bands,
    List<String> singleSource) {

  /** 성장 지원 배율. bands = 할인이 붙는 구간 시작(0·10). */
  public record GrowthSupport(
      List<Integer> bands,
      double material,
      double breath,
      double shard,
      double silver,
      double gold) {}

  /**
   * 부위(ARMOR·WEAPON)의 한 구간. materials는 기본 재료(이름·개수, 파편 포함), breath는 숨결 한 번 쓰는 개수, book은 책 이름(한 번
   * 1개), silver는 실링(모르면 null, 금으로 바꾸지 않는다).
   */
  public record Band(
      int start,
      List<RefiningRules.Material> materials,
      long gold,
      RefiningRules.Material breath,
      String book,
      Long silver,
      boolean singleSource) {}

  public Band band(String type, int stage) {
    var list = bands.get(type);
    if (list == null) return null;
    int start = stage / 10 * 10;
    return list.stream().filter(band -> band.start() == start).findFirst().orElse(null);
  }

  /** 성장 지원이 붙는 구간인지(1~20단계). */
  public boolean supported(int bandStart) {
    return growthSupport != null && growthSupport.bands().contains(bandStart);
  }
}
