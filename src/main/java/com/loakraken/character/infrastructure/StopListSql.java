package com.loakraken.character.infrastructure;

/**
 * 저장된 기록을 읽을 때 제외 목록(DB 표 public_exclusion) 캐릭터를 빼는 조건. 캐릭터 표 별칭은 c. 서버를 비운 항목은 모든 서버의 같은 이름이다. 목록에
 * 들기 전에 저장된 기록도 든 때부터 읽히지 않는다(삭제는 보유 기간 정리가 한다).
 */
final class StopListSql {
  static final String NOT_EXCLUDED =
      " AND NOT EXISTS (SELECT 1 FROM public_exclusion e"
          + " WHERE e.character_name = c.character_name"
          + " AND (e.server_name IS NULL OR e.server_name = c.server_name))";

  private StopListSql() {}
}
