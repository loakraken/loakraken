package com.loakraken.character.domain;

/**
 * 공식 전투정보실 글(툴팁·설명 한 칸)의 길이 상한. 태그를 떼는 패턴들은 닫는 '>'가 없는 긴 '<' 줄에서 시작점마다 끝까지 다시 훑어 길이의 제곱으로 느려지므로,
 * 패턴에 넣기 전에 한 칸씩 길이를 본다. 실제 응답 163개에서 가장 긴 칸은 5,317자(스킬 툴팁)라 그 세 배를 넘게 둔다. 넘는 칸은 깨진 응답으로 보고 읽지
 * 않는다(빈 값).
 */
public final class OfficialTextLimit {
  public static final int MAX_CHARS = 16_384;

  private OfficialTextLimit() {}

  /** 상한 안이면 true(null도 true — 읽을 것이 없을 뿐 길지 않다). */
  public static boolean fits(String text) {
    return text == null || text.length() <= MAX_CHARS;
  }
}
