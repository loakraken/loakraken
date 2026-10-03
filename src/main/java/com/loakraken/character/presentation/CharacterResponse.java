package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "캐릭터 프로필, 전투정보와 원정대 캐릭터 조회 결과")
public record CharacterResponse(
    String characterName,
    String serverName,
    String className,
    @NullableResponse Integer characterLevel,
    BigDecimal itemLevel,
    @NullableResponse BigDecimal combatPower,
    @NullableResponse Integer expeditionLevel,
    @NullableResponse String imageUrl,
    @NullableResponse String title,
    @NullableResponse String guildName,
    List<StatResponse> stats,
    CharacterLoadoutResponse loadout,
    List<SiblingResponse> siblings,
    CharacterAnalysisResponse analysis,
    @Schema(description = "외부 API에서 실제 수집한 UTC 시각. 캐시 조회에서도 유지됩니다.") Instant fetchedAt) {
  public static CharacterResponse from(CharacterResult result) {
    var profile = result.details().profile();
    return new CharacterResponse(
        profile.characterName(),
        profile.serverName(),
        profile.className(),
        profile.characterLevel(),
        profile.itemLevel(),
        profile.combatPower(),
        profile.expeditionLevel(),
        profile.imageUrl(),
        profile.title(),
        profile.guildName(),
        profile.stats().stream().map(stat -> new StatResponse(stat.type(), stat.value())).toList(),
        CharacterLoadoutResponse.from(result.details().loadout()),
        result.details().siblings().stream()
            .map(
                sibling ->
                    new SiblingResponse(
                        sibling.characterName(),
                        sibling.serverName(),
                        sibling.className(),
                        sibling.characterLevel(),
                        sibling.itemLevel()))
            .toList(),
        CharacterAnalysisResponse.from(result.analysis()),
        result.fetchedAt());
  }

  public record StatResponse(@NullableResponse String type, @NullableResponse String value) {}

  public record SiblingResponse(
      String characterName,
      String serverName,
      String className,
      @NullableResponse Integer characterLevel,
      BigDecimal itemLevel) {}
}
