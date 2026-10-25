package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterSibling;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * 제외 목록 캐릭터를 이름으로 바로 찾을 때의 처리(character.stop-list.lookups).
 *
 * <ul>
 *   <li>ALLOW(기본): 지금 처리방침대로 이름으로 바로 찾으면 공식 실시간 응답은 그대로 준다(기록하지 않음, #363). 저장된 기록은 어느 쪽이든 읽지 않는다.
 *   <li>HIDE: 이름으로 바로 찾아도 없는 캐릭터와 같은 응답이고, 원정대 목록에서도 뺀다.
 * </ul>
 *
 * HIDE에서 목록을 확인하지 못하면 응답을 내지 않는다(503).
 *
 * <p><b>HIDE는 아직 켜지 않는다(운영 기본 ALLOW).</b> 다음 두 곳이 HIDE를 따르지 않아, 켜기 전에 먼저 고쳐야 한다.
 *
 * <ul>
 *   <li>원정대 포함 검색(includeSiblings=true): 찾은 캐릭터만 확인하고, 응답 안의 원정대 목록은 걸러지지 않는다(캐시 적중 포함).
 *   <li>캐시를 바로 읽는 조회(아이템 툴팁, 전투력 시뮬레이션·기여도, 스펙업, 빌드 캐릭터, 전투정보실 페이지): requireVisible을 거치지 않아, 목록에 들기
 *       전에 캐시된 캐릭터는 그대로 답한다.
 * </ul>
 */
@Component
public class StopListLookups {
  public enum Mode {
    ALLOW,
    HIDE
  }

  private final CharacterStopList stopList;
  private final Mode mode;

  public StopListLookups(
      CharacterStopList stopList, @Value("${character.stop-list.lookups:ALLOW}") Mode mode) {
    this.stopList = stopList;
    this.mode = mode;
  }

  /** 지금과 같은 처리(테스트·수동 생성용). */
  static StopListLookups allow() {
    return new StopListLookups(null, Mode.ALLOW);
  }

  public Mode mode() {
    return mode;
  }

  /** HIDE이고 그 캐릭터가 목록에 있으면 true. */
  public boolean hidden(String characterName, String serverName) {
    if (mode == Mode.ALLOW || characterName == null) return false;
    try {
      return stopList.contains(characterName, serverName);
    } catch (DataAccessException | TransactionException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE);
    }
  }

  /** HIDE이고 목록에 있으면 없는 캐릭터와 같은 404. */
  public void requireVisible(String characterName, String serverName) {
    if (hidden(characterName, serverName))
      throw new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND);
  }

  /** HIDE면 목록 캐릭터를 뺀 원정대 목록. ALLOW면 그대로. */
  public List<CharacterSibling> visible(List<CharacterSibling> siblings) {
    if (mode == Mode.ALLOW || siblings == null || siblings.isEmpty()) return siblings;
    var byServer = new LinkedHashMap<String, List<String>>();
    for (var sibling : siblings)
      if (sibling.characterName() != null && sibling.serverName() != null)
        byServer
            .computeIfAbsent(sibling.serverName(), server -> new ArrayList<>())
            .add(sibling.characterName());
    var listed = new HashSet<String>();
    try {
      for (var entry : byServer.entrySet())
        for (var name : stopList.listed(entry.getValue(), entry.getKey()))
          listed.add(entry.getKey() + "\n" + name);
    } catch (DataAccessException | TransactionException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE);
    }
    if (listed.isEmpty()) return siblings;
    return siblings.stream()
        .filter(s -> !listed.contains(s.serverName() + "\n" + s.characterName()))
        .toList();
  }
}
