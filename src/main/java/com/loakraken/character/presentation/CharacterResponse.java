package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "캐릭터 프로필 조회 결과. 후속 작업에서 전투정보 섹션을 추가합니다.")
public record CharacterResponse(
    String characterName,
    String serverName,
    String className,
    Integer characterLevel,
    BigDecimal itemLevel,
    BigDecimal combatPower,
    Integer expeditionLevel,
    String imageUrl,
    String title,
    String guildName,
    List<StatResponse> stats,
    @Schema(description = "외부 API에서 실제 수집한 UTC 시각. 캐시 조회에서도 유지됩니다.") Instant fetchedAt) {
  public static CharacterResponse from(CharacterResult result) {
    var profile = result.profile();
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
        result.fetchedAt());
  }

  public record StatResponse(String type, String value) {}
}
