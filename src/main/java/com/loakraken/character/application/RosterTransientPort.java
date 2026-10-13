package com.loakraken.character.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 원정대 전투력 불러오기(L19)에서 기록하지 않는 캐릭터(처리정지 목록)의 임시 값과 '불러옴' 표시.
 *
 * <ul>
 *   <li>임시 값: 받은 전투력을 짧은 TTL(5분)로만 두고 응답에 쓴다. 처리방침 6항(직접 검색하면 현재 정보를 보여 주되 5분 임시 보관 후 삭제)과 같은 동작.
 *   <li>'불러옴' 표시: 값 없이 TTL(staleAfter, 24시간)로만 둔다. 다음 불러오기에서 다른 캐릭터처럼 대상에서 빠지게 해, 숫자로 처리정지가 드러나지 않게
 *       한다.
 * </ul>
 *
 * 키는 서버 비밀값 HMAC이라 이름 원문을 두지 않는다. DB에 쓰지 않는다. 저장소 장애면 DataAccessException을 던진다(불러오기는 fail closed
 * 503, 조회는 없는 값으로 둔다).
 */
public interface RosterTransientPort {
  record Value(BigDecimal combatPower, Instant observedAt) {}

  void put(String serverName, String characterName, Value value, Duration ttl);

  /** 아직 남아 있는 임시 값(캐릭터 이름 → 값). */
  Map<String, Value> find(String serverName, Set<String> characterNames);

  void mark(String serverName, String characterName, Duration ttl);

  /** '불러옴' 표시가 남아 있는 캐릭터 이름. */
  Set<String> marked(String serverName, Set<String> characterNames);
}
