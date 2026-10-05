package com.loakraken.character.application;

import com.loakraken.character.domain.CritSpeedCalculation;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** 캐릭터 검색 결과(캐시 공유)로 치명타·공이속을 계산한다. 원정대는 조회하지 않는다. */
@Service
public class CritSpeedService {
  private final CharacterQueryService characters;

  public CritSpeedService(CharacterQueryService characters) {
    this.characters = characters;
  }

  public record CritSpeedResult(
      String characterName,
      String className,
      Instant fetchedAt,
      CritSpeedCalculation.Result result) {}

  public CritSpeedResult calculate(
      String characterName, CritSpeedCalculation.Assumptions assumptions) {
    var found = characters.search(new SearchCharacterCommand(characterName, false));
    var profile = found.details().profile();
    return new CritSpeedResult(
        profile.characterName(),
        profile.className(),
        found.fetchedAt(),
        CritSpeedCalculation.calculate(profile, found.details().loadout(), assumptions));
  }
}
