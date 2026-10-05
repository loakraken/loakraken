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
    @Schema(description = "성향(지성·담력·매력·친절)") List<TendencyResponse> tendencies,
    @Schema(description = "장식(심볼·문장). 공식 프로필에 없으면 null") @NullableResponse
        DecorationsResponse decorations,
    @NullableResponse Integer honorPoint,
    @Schema(description = "영지 이름") @NullableResponse String townName,
    @NullableResponse Integer townLevel,
    @NullableResponse String guildMemberGrade,
    @Schema(description = "장착 아바타. 덧입기는 isInner=true. 염색 정보는 공식 응답에서 확인되지 않아 제공하지 않습니다.")
        List<AvatarResponse> avatars,
    @Schema(description = "수집품 종류별 진행도와 항목") List<CollectibleResponse> collectibles,
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
    var inner = profile.inner();
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
        inner == null
            ? List.of()
            : inner.tendencies().stream()
                .map(t -> new TendencyResponse(t.type(), t.point(), t.maxPoint()))
                .toList(),
        inner == null || inner.decorations() == null
            ? null
            : new DecorationsResponse(inner.decorations().symbol(), inner.decorations().emblems()),
        inner == null ? null : inner.honorPoint(),
        inner == null ? null : inner.townName(),
        inner == null ? null : inner.townLevel(),
        inner == null ? null : inner.guildMemberGrade(),
        result.details().loadout().avatars().stream()
            .map(
                a ->
                    new AvatarResponse(
                        a.type(),
                        a.name(),
                        a.icon(),
                        a.grade(),
                        a.isSet(),
                        a.isInner(),
                        a.effects()))
            .toList(),
        result.details().loadout().collectibles().stream()
            .map(
                c ->
                    new CollectibleResponse(
                        c.type(),
                        c.icon(),
                        c.point(),
                        c.maxPoint(),
                        c.items().stream()
                            .map(
                                i -> new CollectibleItemResponse(i.name(), i.point(), i.maxPoint()))
                            .toList()))
            .toList(),
        CharacterLoadoutResponse.from(result.details().loadout()),
        result.details().siblings() == null
            ? null
            : SiblingResponse.from(result.details().siblings()),
        CharacterObservationCoverageResponse.from(result.details().coverage()),
        CharacterAnalysisResponse.from(result.analysis(), result.details().coverage()),
        result.fetchedAt(),
        result.refreshAvailableAt());
  }

  public record TendencyResponse(
      @NullableResponse String type,
      @NullableResponse Integer point,
      @NullableResponse Integer maxPoint) {}

  public record DecorationsResponse(@NullableResponse String symbol, List<String> emblems) {}

  public record AvatarResponse(
      @NullableResponse String type,
      @NullableResponse String name,
      @NullableResponse String icon,
      @NullableResponse String grade,
      @NullableResponse Boolean isSet,
      @NullableResponse Boolean isInner,
      @Schema(description = "아바타 기본 효과 줄(예: 민첩 +2.00%)") List<String> effects) {}

  public record CollectibleResponse(
      @NullableResponse String type,
      @NullableResponse String icon,
      @NullableResponse Integer point,
      @NullableResponse Integer maxPoint,
      List<CollectibleItemResponse> items) {}

  public record CollectibleItemResponse(
      @NullableResponse String name,
      @NullableResponse Integer point,
      @NullableResponse Integer maxPoint) {}

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
