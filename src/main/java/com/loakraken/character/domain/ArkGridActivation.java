package com.loakraken.character.domain;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 아크 그리드 젬 활성화(공식 게임 가이드 '아크 그리드', 최근수정일 2026-06-24, 읽음 2026-11-22 — 계산 검증 확인). 장착한 젬은 코어가 공급하는
 * 의지력으로 켜지고, 코어 슬롯에서 가까운 젬 슬롯부터 차례로 공급된다. 젬이 없거나 필요 의지력이 모자란 슬롯부터 그 뒤 슬롯은 모두 꺼진다(젬마다 켜짐·꺼짐 둘 중 하나).
 *
 * <p>공식 Gems[].Index 0이 코어에 가장 가까운 슬롯이라고 본다(API 순서와 맞지만 꺼진 젬이 있는 캡처로는 아직 확인하지 않았다). 그래서 부르는 쪽은 이
 * 규칙이 지금 공식 IsActive를 그대로 재현할 때만 쓴다.
 */
public final class ArkGridActivation {
  private ArkGridActivation() {}

  /**
   * 한 번 차례로 훑어 켜지는 젬 Index.
   *
   * @param supply 코어 공급 의지력
   * @param needs Index → 그 슬롯 젬의 필요 의지력(젬이 없는 슬롯은 키 없음)
   */
  public static Set<Integer> active(int supply, Map<Integer, Integer> needs) {
    var active = new TreeSet<Integer>();
    int last = needs.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
    int remaining = supply;
    for (int index = 0; index <= last; index++) {
      var need = needs.get(index);
      if (need == null || need > remaining) break;
      active.add(index);
      remaining -= need;
    }
    return active;
  }
}
