package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterLoadout;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 아이템 hover 툴팁의 나머지 줄(#227, PM-백엔드·PM-프론트 10-10 11:36·11:37 분리안). 캐릭터 검색 응답을 가볍게 두려고 공식 Tooltip의
 * extraLines만 따로 준다. <b>공식 API를 부르지 않는다</b>: 캐릭터 캐시만 읽고, 없으면 CHARACTER_NOT_CACHED(404)다. 공개 제외 캐릭터는
 * 검색·공개 요약과 같이 숨김(빈 목록)이다.
 */
@Service
public class CharacterItemTooltipService {
  private final CharacterCache cache;
  private final CharacterPublicSummaryPort stored;

  public CharacterItemTooltipService(CharacterCache cache, CharacterPublicSummaryPort stored) {
    this.cache = cache;
    this.stored = stored;
  }

  /** index는 검색 응답 loadout.equipment 배열 순번(0부터), type은 그 항목의 type이다. */
  public record EquipmentLines(int index, String type, List<String> extraLines) {}

  public record SlotLines(Integer slot, List<String> extraLines) {}

  /** name은 검색 응답 loadout.skills[].name이다. */
  public record SkillLines(String name, List<String> runeExtraLines) {}

  /** fetchedAt: 이 줄을 읽은 캐릭터 캐시의 수집 시각(검색 응답 fetchedAt과 같으면 순번이 맞다). 숨김이면 null. */
  public record Tooltips(
      String characterName,
      boolean hidden,
      java.time.Instant fetchedAt,
      List<EquipmentLines> equipment,
      List<SlotLines> gems,
      List<SlotLines> cards,
      List<SkillLines> skills) {
    static Tooltips hidden(String characterName) {
      return new Tooltips(characterName, true, null, List.of(), List.of(), List.of(), List.of());
    }
  }

  public Tooltips tooltips(String characterName) {
    var name = new SearchCharacterCommand(characterName).characterName();
    CharacterResult result;
    try {
      result = cache.find(name).or(() -> cache.findArmory(name)).orElse(null);
    } catch (CharacterException exception) {
      // 캐시 장애여도 공식 호출로 넘기지 않는다.
      result = null;
    }
    boolean excluded;
    try {
      var profile = result == null ? null : result.details().profile();
      excluded =
          profile == null
              ? stored.excludedAnyServer(name)
              : stored.excluded(profile.characterName(), profile.serverName());
    } catch (DataAccessException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE);
    }
    if (excluded)
      return Tooltips.hidden(result == null ? name : result.details().profile().characterName());
    if (result == null) throw new CharacterException(CharacterErrorCode.CHARACTER_NOT_CACHED);
    return from(
        result.details().profile().characterName(), result.fetchedAt(), result.details().loadout());
  }

  static Tooltips from(
      String characterName, java.time.Instant fetchedAt, CharacterLoadout loadout) {
    var equipment = new ArrayList<EquipmentLines>();
    for (int i = 0; i < loadout.equipment().size(); i++) {
      var item = loadout.equipment().get(i);
      if (item == null || item.detail() == null || item.detail().extraLines().isEmpty()) continue;
      equipment.add(new EquipmentLines(i, item.type(), item.detail().extraLines()));
    }
    var gems = new ArrayList<SlotLines>();
    if (loadout.gems() != null)
      for (var gem : loadout.gems().items())
        if (gem != null && !gem.extraLines().isEmpty())
          gems.add(new SlotLines(gem.slot(), gem.extraLines()));
    var cards = new ArrayList<SlotLines>();
    if (loadout.cards() != null)
      for (var card : loadout.cards().items())
        if (card != null && !card.extraLines().isEmpty())
          cards.add(new SlotLines(card.slot(), card.extraLines()));
    var skills = new ArrayList<SkillLines>();
    if (loadout.skills() != null)
      for (var skill : loadout.skills())
        if (skill != null && skill.rune() != null && !skill.rune().extraLines().isEmpty())
          skills.add(new SkillLines(skill.name(), skill.rune().extraLines()));
    return new Tooltips(
        characterName,
        false,
        fetchedAt,
        List.copyOf(equipment),
        List.copyOf(gems),
        List.copyOf(cards),
        List.copyOf(skills));
  }
}
