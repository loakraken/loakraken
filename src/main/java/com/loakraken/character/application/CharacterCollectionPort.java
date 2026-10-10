package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterSibling;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 통계 표본 수집 대상 선정과 결과 저장. */
public interface CharacterCollectionPort {
  record Target(String characterName, String serverName) {}

  /** minItemLevel 이상이고 프로필이 없거나 staleBefore 이전에 갱신된 캐릭터 중 가장 오래된 하나. */
  Optional<Target> nextProfileDue(BigDecimal minItemLevel, Instant staleBefore);

  /** 원정대를 훑은 적이 없거나 staleBefore 이전에 훑은 캐릭터 중 아이템 레벨이 높은 하나. */
  Optional<Target> nextRosterDue(Instant staleBefore);

  /** 원정대 캐릭터를 추가·갱신하고 모두 훑은 것으로 표시한다. 반환은 새로 발견한 캐릭터 수. */
  int saveRoster(List<CharacterSibling> siblings, Instant now);

  void markProfileRefreshed(Target target, String position, Instant now);

  /** 공식 API가 캐릭터를 찾지 못하면 다시 고르지 않도록 표시한다. */
  void markProfileMissing(Target target, Instant now);

  void markRosterScanned(Target target, Instant now);

  /** 시드(#255): 이 이름의 캐릭터가 있고 원정대를 이미 훑었으면 true(시드를 다시 부르지 않는다). */
  boolean rosterKnown(String characterName);
}
