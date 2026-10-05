package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterResult;
import com.loakraken.character.domain.CharacterSibling;
import com.loakraken.common.api.NullableResponse;
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
    @Schema(description = "스킬 포인트 사용/전체. 공식 프로필에 없으면 null") @NullableResponse
        SkillPointsResponse skillPoints,
    CharacterLoadoutResponse loadout,
    @Schema(description = "원정대 캐릭터. includeSiblings=false 요청에서는 조회하지 않아 null입니다.") @NullableResponse
        List<SiblingResponse> siblings,
    CharacterObservationCoverageResponse coverage,
    CharacterAnalysisResponse analysis,
    @Schema(description = "외부 API에서 실제 수집한 UTC 시각. 캐시 조회에서도 유지됩니다.") Instant fetchedAt,
    @Schema(
            description =
                "캐시 만료 예정 UTC 시각. 이 시각 이전 요청은 캐시를 반환해 fetchedAt이 바뀌지 않습니다. 캐시 적중에서도 유지되며 실제 만료는 1초 미만 늦을 수 있습니다. 알 수 없으면 null입니다.")
        @NullableResponse
        Instant refreshAvailableAt) {
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
        profile.skillPoints() == null
            ? null
            : new SkillPointsResponse(profile.skillPoints().used(), profile.skillPoints().total()),
        CharacterLoadoutResponse.from(result.details().loadout()),
        result.details().siblings() == null
            ? null
            : SiblingResponse.from(result.details().siblings()),
        CharacterObservationCoverageResponse.from(result.details().coverage()),
        CharacterAnalysisResponse.from(result.analysis(), result.details().coverage()),
        result.fetchedAt(),
        result.refreshAvailableAt());
  }

  public record SkillPointsResponse(
      @NullableResponse Integer used, @NullableResponse Integer total) {}

  public record StatResponse(@NullableResponse String type, @NullableResponse String value) {}

  public record SiblingResponse(
      String characterName,
      String serverName,
      String className,
      @NullableResponse Integer characterLevel,
      BigDecimal itemLevel) {
    static List<SiblingResponse> from(List<CharacterSibling> siblings) {
      return siblings.stream()
          .map(
              sibling ->
                  new SiblingResponse(
                      sibling.characterName(),
                      sibling.serverName(),
                      sibling.className(),
                      sibling.characterLevel(),
                      sibling.itemLevel()))
          .toList();
    }
  }
}
