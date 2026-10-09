package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterSibling;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * 원정대 보유 칭호(title-record-v1, 배정 E). 공식 API는 현재 장착 칭호 하나만 주고 보유 목록·획득일이 없다. 그래서 원정대 캐릭터가 검색·수집될 때
 * 장착한 칭호를 기록해 LOAKraken이 처음 확인한 순서로 준다. 칭호는 원정대가 공유하므로 같은 서버 원정대로 묶는다.
 *
 * <p>실제 획득 순서·획득일이 아니다. 장착하지 않은 칭호는 기록되지 않는다(PM 총괄 노출 기준).
 */
@Service
public class CharacterTitleService {
  public static final String RULE_VERSION = "title-record-v1";
  private final CharacterHistoryPort history;
  private final CharacterHistoryPolicy policy;

  public CharacterTitleService(CharacterHistoryPort history, CharacterHistoryPolicy policy) {
    this.history = history;
    this.policy = policy;
  }

  public RosterTitles roster(String characterName, List<CharacterSibling> siblings) {
    if (!policy.enabled())
      throw new CharacterException(CharacterErrorCode.CHARACTER_HISTORY_DISABLED);
    var self =
        siblings.stream()
            .filter(sibling -> characterName.equalsIgnoreCase(sibling.characterName()))
            .findFirst()
            .orElseThrow(() -> new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND));
    String server = self.serverName();
    var roster = new LinkedHashSet<String>();
    for (var sibling : siblings)
      if (server.equals(sibling.serverName()) && sibling.characterName() != null)
        roster.add(sibling.characterName());
    try {
      var titles = history.rosterTitles(server, roster);
      var items = new ArrayList<Title>();
      int order = 1;
      for (var title : titles)
        items.add(
            new Title(
                title.title(),
                title.firstObservedAt(),
                title.lastObservedAt(),
                order++,
                title.recordedBy()));
      return new RosterTitles(
          self.characterName(),
          server,
          roster.size(),
          history.titleObservedCharacters(server, roster),
          items);
    } catch (DataAccessException | TransactionException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_HISTORY_UNAVAILABLE);
    }
  }

  public record RosterTitles(
      String characterName,
      String serverName,
      int rosterCharacters,
      int observedCharacters,
      List<Title> titles) {}

  /** observedOrder는 처음 확인한 순서(1부터)다. 획득 순서가 아니다. */
  public record Title(
      String title,
      java.time.Instant firstObservedAt,
      java.time.Instant lastObservedAt,
      int observedOrder,
      String recordedBy) {}
}
