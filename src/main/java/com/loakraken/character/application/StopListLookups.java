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
