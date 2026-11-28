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
    // 끄기 스위치는 캐릭터를 찾기(공식 조회일 수 있음) 전에 본다 — 꺼 둔 화면은 공식 호출 0(보안 v0.3.16 조건 2, #1129와 같은 규칙).
    var request = new SearchCharacterCommand(characterName, false);
    if (guard.killed(CombatPowerGuard.Surface.CRIT_SPEED)) {
      guard.blocks((java.util.List<String>) null, CombatPowerGuard.Surface.CRIT_SPEED);
      throw new CharacterException(CharacterErrorCode.GAME_UPDATE_UNSUPPORTED);
    }
    var found = characters.search(request);
    // 2부 대비 막기(계약 2026-11-28): 응답에 상태 칸이 없어 422 GAME_UPDATE_UNSUPPORTED로 막는다(숫자 없음). 판정을 알려면 찾은 뒤라야
    // 한다.
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
