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
    @Schema(
            description =
                "팔찌 효율(전투력 기준): 지금 전투력이 팔찌(효과 줄 전부와 팔찌 도약 포인트)를 뺀 전투력보다 몇 % 높은지(지금 ÷ 뺀 전투력 − 1)와 줄별 값(그 줄만 뺐을 때, 팔찌 도약은 따로 한 줄). 줄별 값은 그 줄만 뺐을 때의 값이라 곱 구조상 줄별 합은 전체 값과 같지 않습니다. 팔찌가 없으면 null")
        @NullableResponse
        BraceletEfficiencyResponse braceletEfficiency,
    @Schema(
            description =
                "아크 그리드 효율(전투력 기준, L29·L39): 지금 전투력이 아크 그리드 코어와 젬 옵션을 모두 뺀 전투력보다 몇 % 높은지(지금 ÷ 뺀 전투력 − 1), 코어만의 합(coresPercent), 줄별 값(코어마다 그 코어만 뺐을 때, 마지막 줄은 젬 옵션 합계). 줄별 값은 그 줄만 뺐을 때의 값이라 곱 구조상 줄별 합은 전체 값과 같지 않습니다. 아크 그리드가 없으면 null")
        @NullableResponse
        ArkGridEfficiencyResponse arkGridEfficiency,
    @Schema(description = "외부 API에서 실제 수집한 UTC 시각. 캐시 조회에서도 유지됩니다.") Instant fetchedAt,
    @Schema(
            description =
                "캐시 만료 예정 UTC 시각. 이 시각 이전 요청은 캐시를 반환해 fetchedAt이 바뀌지 않습니다. 캐시 적중에서도 유지되며 실제 만료는 1초 미만 늦을 수 있습니다. 알 수 없으면 null입니다.")
        @NullableResponse
        Instant refreshAvailableAt) {
  public static CharacterResponse from(CharacterResult result) {
    return from(result, null, null);
  }

  public static CharacterResponse from(
      CharacterResult result,
      com.loakraken.character.application.CombatPowerSimulationService.BraceletEfficiency
          efficiency,
      com.loakraken.character.application.ArkGridEfficiencyService.ArkGridEfficiency arkGrid) {
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
        profile.stats().stream()
            .map(stat -> new StatResponse(stat.type(), stat.value(), stat.tooltip()))
            .toList(),
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
        BraceletEfficiencyResponse.from(efficiency),
        ArkGridEfficiencyResponse.from(arkGrid),
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

  public record StatResponse(
      @NullableResponse String type,
      @NullableResponse String value,
      @Schema(description = "공식 스탯 Tooltip 문장(정제). 예: 치명타 적중률이 20.61% 증가합니다.")
          List<String> tooltip) {}

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

  public record BraceletEfficiencyResponse(
      @Schema(description = "팔찌 전체 효율 %(소수 둘째 자리). UNSUPPORTED면 null", example = "14.42")
          @NullableResponse
          BigDecimal percent,
      @Schema(description = "OK 또는 UNSUPPORTED(문자열)", example = "OK") String status,
      @Schema(description = "UNSUPPORTED 이유 코드(시뮬레이터·스펙업과 같은 코드). OK면 null") @NullableResponse
          String reasonCode,
      @Schema(description = "근거 문장 또는 계산하지 않은 이유") String reason,
      @Schema(description = "팔찌 효과 줄마다 그 줄만 뺐을 때의 효율(공식 순서), 팔찌 도약 포인트가 있으면 마지막에 '팔찌 도약 +N' 한 줄")
          List<BraceletLineEfficiencyResponse> lines,
      @Schema(description = "계산 규칙 버전(전투력 시뮬레이터와 같음)") String ruleVersion) {
    static BraceletEfficiencyResponse from(
        com.loakraken.character.application.CombatPowerSimulationService.BraceletEfficiency value) {
      if (value == null) return null;
      return new BraceletEfficiencyResponse(
          value.percent(),
          value.status().name(),
          value.reasonCode(),
          value.reason(),
          value.lines().stream()
              .map(
                  line ->
                      new BraceletLineEfficiencyResponse(
                          line.text(),
                          line.percent(),
                          line.status().name(),
                          line.reasonCode(),
                          line.reason()))
              .toList(),
          value.ruleVersion());
    }
  }

  public record ArkGridEfficiencyResponse(
      @Schema(
              description = "아크 그리드 전체 효율 %(코어 × 젬 옵션, 소수 둘째 자리). UNSUPPORTED면 null",
              example = "38.64")
          @NullableResponse
          BigDecimal percent,
      @Schema(
              description = "코어만의 합 %(Π 코어 배수 − 1, 소수 둘째 자리). 계산하지 못한 코어가 있으면 null",
              example = "32.96")
          @NullableResponse
          BigDecimal coresPercent,
      @Schema(description = "OK 또는 UNSUPPORTED(문자열)", example = "OK") String status,
      @Schema(description = "UNSUPPORTED 이유 코드(시뮬레이터·스펙업과 같은 코드). OK면 null") @NullableResponse
          String reasonCode,
      @Schema(description = "근거 문장 또는 계산하지 않은 이유") String reason,
      @Schema(description = "코어마다 한 줄(공식 슬롯 순서), 마지막에 젬 옵션 합계 한 줄")
          List<ArkGridLineEfficiencyResponse> lines,
      @Schema(description = "계산 규칙 버전") String ruleVersion) {
    static ArkGridEfficiencyResponse from(
        com.loakraken.character.application.ArkGridEfficiencyService.ArkGridEfficiency value) {
      if (value == null) return null;
      return new ArkGridEfficiencyResponse(
          value.percent(),
          value.coresPercent(),
          value.status().name(),
          value.reasonCode(),
          value.reason(),
          value.lines().stream()
              .map(
                  line ->
                      new ArkGridLineEfficiencyResponse(
                          line.slotIndex(),
                          line.text(),
                          line.percent(),
                          line.status().name(),
                          line.reasonCode(),
                          line.reason()))
              .toList(),
          value.ruleVersion());
    }
  }

  public record ArkGridLineEfficiencyResponse(
      @Schema(
              description =
                  "코어 줄이면 아크 그리드 슬롯 index(loadout.arkGrid.slots[].index), 젬 옵션 합계 줄이면 null")
          @NullableResponse
          Integer slotIndex,
      @Schema(description = "코어 이름·등급·포인트 또는 '젬 옵션(공격력 Lv.N · …)'") String text,
      @Schema(description = "지금 전투력이 그 줄만 뺀 전투력보다 몇 % 높은지(소수 둘째 자리). UNSUPPORTED면 null")
          @NullableResponse
          BigDecimal percent,
      @Schema(description = "OK 또는 UNSUPPORTED(문자열)") String status,
      @NullableResponse String reasonCode,
      @Schema(description = "계산하지 않은 이유(OK면 null)") @NullableResponse String reason) {}

  public record BraceletLineEfficiencyResponse(
      @Schema(description = "공식 팔찌 효과 문구(팔찌 도약 줄은 '팔찌 도약 +N')") String text,
      @Schema(description = "지금 전투력이 그 줄만 뺀 전투력보다 몇 % 높은지(소수 둘째 자리). UNSUPPORTED면 null")
          @NullableResponse
          BigDecimal percent,
      @Schema(description = "OK 또는 UNSUPPORTED(문자열)") String status,
      @NullableResponse String reasonCode,
      @Schema(description = "계산하지 않은 이유(OK면 null)") @NullableResponse String reason) {}
}
