package com.loakraken.stats.infrastructure;

/**
 * 처리정지 목록(DB 표 public_exclusion)의 지금 내용을 나타내는 짧은 값. 이름·서버를 정렬해 이은 문자열의 md5 앞 12자리이고, 목록이 비면 "0"이다.
 * 목록은 운영자가 DB에 직접 바꾸므로 바뀐 시점을 알 수 없어, 집계 캐시 키에 이 값을 넣어 목록이 바뀌면 바로 새로 센다.
 */
final class StopListRevisionSql {
  static final String REVISION =
      "SELECT COALESCE(LEFT(md5(string_agg(character_name || '|' || COALESCE(server_name, ''), ','"
          + " ORDER BY character_name, server_name)), 12), '0') FROM public_exclusion";

  private StopListRevisionSql() {}
}
