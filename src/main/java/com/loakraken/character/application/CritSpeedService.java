package com.loakraken.character.application;

import com.loakraken.character.domain.CritSpeedCalculation;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** 캐릭터 검색 결과(캐시 공유)로 치명타·공이속을 계산한다. 원정대는 조회하지 않는다. */
@Service
public class CritSpeedService {
  private final CharacterQueryService characters;
  private final CombatPowerGuard guard;

  public CritSpeedService(CharacterQueryService characters, CombatPowerGuard guard) {
    this.characters = characters;
    this.guard = guard;
  }

  public record CritSpeedResult(
      String characterName,
      String className,
      Instant fetchedAt,
      CritSpeedCalculation.Result result) {}

  public CritSpeedResult calculate(
      String characterName, CritSpeedCalculation.Assumptions assumptions) {
    var found = characters.search(new SearchCharacterCommand(characterName, false));
    // 2부 대비 막기(계약 2026-11-28): 응답에 상태 칸이 없어 422 GAME_UPDATE_UNSUPPORTED로 막는다(숫자 없음).
    if (guard.blocks(found, CombatPowerGuard.Surface.CRIT_SPEED))
      throw new CharacterException(CharacterErrorCode.GAME_UPDATE_UNSUPPORTED);
    var profile = found.details().profile();
    return new CritSpeedResult(
        profile.characterName(),
        profile.className(),
        found.fetchedAt(),
        CritSpeedCalculation.calculate(profile, found.details().loadout(), assumptions));
  }
}
