package com.loakraken.character.domain;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 공식 전투정보실 글(툴팁·설명 한 칸)의 길이 상한. 태그를 떼는 패턴들은 닫는 '>'가 없는 긴 '<' 줄에서 시작점마다 끝까지 다시 훑어 길이의 제곱으로 느려지므로,
 * 패턴에 넣기 전에 한 칸씩 길이를 본다. 실제 응답 163개에서 가장 긴 칸은 5,317자(스킬 툴팁)라 그 세 배를 넘게 둔다. 넘는 칸은 깨진 응답으로 보고 읽지
 * 않는다(빈 값).
 *
 * <p>읽지 않은 칸은 계산 입력이 빠지므로 수를 센다({@link #dropped()}, 지표 loakraken.official_text.dropped). 세는 것은 칸이
 * 아니라 상한에 걸린 읽기 횟수다: 한 툴팁은 여러 파서(장비 툴팁·점수 입력·표시 글)가 읽으므로 긴 칸 하나가 2~4번 셀 수 있다. 0인지 아닌지(계산 입력이 빠지는지)를
 * 보는 지표라 칸 단위로 맞추지 않는다(PM 총괄 11-21 P3).
 */
public final class OfficialTextLimit {
  public static final int MAX_CHARS = 16_384;

  private static final AtomicLong DROPPED = new AtomicLong();

  private OfficialTextLimit() {}

  /** 상한 안이면 true(null도 true — 읽을 것이 없을 뿐 길지 않다). 넘으면 읽지 않은 횟수를 하나 올린다(부를 때마다). */
  public static boolean fits(String text) {
    if (text == null || text.length() <= MAX_CHARS) return true;
    DROPPED.incrementAndGet();
    return false;
  }

  /** 프로세스 시작부터 상한을 넘어 읽지 않은 횟수(칸 수가 아님, 한 칸을 여러 파서가 읽으면 그만큼). */
  public static long dropped() {
    return DROPPED.get();
  }
}
