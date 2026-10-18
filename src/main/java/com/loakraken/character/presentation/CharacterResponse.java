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
                "아크 그리드 효율(전투력 기준): 지금 전투력이 아크 그리드 코어와 젬 옵션을 모두 뺀 전투력보다 몇 % 높은지(지금 ÷ 뺀 전투력 − 1), 코어만의 합(coresPercent), 줄별 값(코어마다 그 코어만 뺐을 때, 마지막 줄은 젬 옵션 합계). 줄별 값은 그 줄만 뺐을 때의 값이라 곱 구조상 줄별 합은 전체 값과 같지 않습니다. 아크 그리드가 없으면 null")
        @NullableResponse
        ArkGridEfficiencyResponse arkGridEfficiency,
    @Schema(
            description =
                "크라켄 점수와 크라켄 점수 티어(공식 전투력과 별개의 점수). 늘 들어 있고, 아직 공개 전이면 status DISABLED(값 null)")
        KrakenScoreResponse krakenScore,
    @Schema(description = "외부 API에서 실제 수집한 UTC 시각. 캐시 조회에서도 유지됩니다.") Instant fetchedAt,
    @Schema(
            description =
                "캐시 만료 예정 UTC 시각. 이 시각 이전 요청은 캐시를 반환해 fetchedAt이 바뀌지 않습니다. 캐시 적중에서도 유지되며 실제 만료는 1초 미만 늦을 수 있습니다. 알 수 없으면 null입니다.")
        @NullableResponse
        Instant refreshAvailableAt) {
  /** 효율·점수 없이(직렬화 테스트용). 점수는 DISABLED 모양. */
  public static CharacterResponse from(CharacterResult result) {
    return from(result, null, null, KrakenScoreResponse.DISABLED_WITHOUT_VERSION);
  }

  public static CharacterResponse from(
      CharacterResult result,
      com.loakraken.character.application.CombatPowerSimulationService.BraceletEfficiency
          efficiency,
      com.loakraken.character.application.ArkGridEfficiencyService.ArkGridEfficiency arkGrid,
      com.loakraken.character.application.KrakenScoreService.KrakenScore krakenScore) {
    return from(result, efficiency, arkGrid, KrakenScoreResponse.from(krakenScore));
  }

  private static CharacterResponse from(
      CharacterResult result,
      com.loakraken.character.application.CombatPowerSimulationService.BraceletEfficiency
          efficiency,
      com.loakraken.character.application.ArkGridEfficiencyService.ArkGridEfficiency arkGrid,
      KrakenScoreResponse krakenScore) {
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
        krakenScore,
        result.fetchedAt(),
        result.refreshAvailableAt());
  }

  @Schema(
      description =
          "크라켄 점수. status: OK(값 있음)·UNSUPPORTED(계산 못 함, reasonCode)·DISABLED(아직 공개 전, 값 null·factors 빈 배열)."
              + " 점수는 딜러 점수 또는 서포터 점수(서포터 각인 직업), 이번 단계에서 factors는 빈 배열")
  public record KrakenScoreResponse(
      @Schema(
              description = "OK·UNSUPPORTED·DISABLED",
              allowableValues = {"OK", "UNSUPPORTED", "DISABLED"},
              example = "OK")
          String status,
      @Schema(
              description =
                  "UNSUPPORTED 이유 코드(화면은 이 코드로 그림). 그 밖에는 null. 지금 값: INPUT_PENDING(점수 입력이 없는 이전 조회"
                      + " 결과 — 다시 조회하면 계산)·ROLE_UNKNOWN(딜러·서포터 판정 못 함)·SCORE_UNAVAILABLE(점수가 음수·수 아님). 열린"
                      + " 값이라 단계마다 늘 수 있음 — 모르는 코드는 '-'로 그림",
              example = "INPUT_PENDING")
          @NullableResponse
          String reasonCode,
      @Schema(description = "이유 문장(로그·디버그용, 화면 문구로 쓰지 않음). OK·DISABLED면 null") @NullableResponse
          String reason,
      @Schema(
              description = "DEALER·SUPPORT. 판정하지 못했거나 DISABLED면 null",
              allowableValues = {"DEALER", "SUPPORT"})
          @NullableResponse
          String role,
      @Schema(description = "크라켄 점수(소수 둘째 자리). OK가 아니면 null", example = "5658.62") @NullableResponse
          BigDecimal score,
      @Schema(description = "크라켄 점수 티어. OK가 아니면 null") @NullableResponse
          KrakenScoreTierResponse tier,
      @Schema(
              description =
                  "점수 요소(공격력·진화·깨달음·도약·적에게 주는 피해·추가 피해·보주·보석·특성·에스더·팔찌 등). DISABLED면 빈 배열")
          List<KrakenScoreFactorResponse> factors,
      @Schema(description = "계산 규칙 버전(표 갱신 때 올라감)", example = "kraken-score-v1") @NullableResponse
          String ruleVersion) {
    static final KrakenScoreResponse DISABLED_WITHOUT_VERSION =
        new KrakenScoreResponse("DISABLED", null, null, null, null, null, List.of(), null);

    static KrakenScoreResponse from(
        com.loakraken.character.application.KrakenScoreService.KrakenScore value) {
      if (value == null) return DISABLED_WITHOUT_VERSION;
      var tier = value.tier();
      return new KrakenScoreResponse(
          value.status().name(),
          value.reason() == null ? null : value.reason().name(),
          value.reason() == null ? null : value.reason().text(),
          value.role() == null ? null : value.role().name(),
          value.score(),
          tier == null
              ? null
              : new KrakenScoreTierResponse(
                  tier.code(),
                  tier.min(),
                  tier.nextCode(),
                  tier.nextMin(),
                  tier.remaining(),
                  tier.remainingRounded(),
                  tier.progressPercent()),
          value.factors().stream()
              .map(
                  factor ->
                      new KrakenScoreFactorResponse(
                          factor.code(),
                          factor.value(),
                          factor.status().name(),
                          factor.reason() == null ? null : factor.reason().name()))
              .toList(),
          value.ruleVersion());
    }
  }

  public record KrakenScoreTierResponse(
      @Schema(
              description = "티어 코드",
              allowableValues = {
                "BRONZE",
                "SILVER",
                "GOLD",
                "DIAMOND",
                "MASTER",
                "GRANDMASTER",
                "ESTHER"
              },
              example = "DIAMOND")
          String code,
      @Schema(description = "이 티어의 최소 점수", example = "5000") BigDecimal min,
      @Schema(description = "다음 티어 코드. 최상위면 null", example = "MASTER") @NullableResponse
          String nextCode,
      @Schema(description = "다음 티어 최소 점수. 최상위면 null", example = "6700") @NullableResponse
          BigDecimal nextMin,
      @Schema(description = "다음 티어까지 남은 점수(소수 둘째 자리). 최상위면 null", example = "1041.38")
          @NullableResponse
          BigDecimal remaining,
      @Schema(
              description =
                  "다음 티어까지 남은 점수를 정수로(화면용). 원본 화면과 같이 위 remaining(소수 둘째 자리)을 다시 .5는 위로 정수 반올림한 값(예: 242.497 → 242.50 → 243). 화면은 이 값을 그대로 쓴다. 최상위면 null",
              example = "1041")
          @NullableResponse
          Integer remainingRounded,
      @Schema(description = "이 티어 안 진행률(정수, 내림). 최상위면 100", example = "38") int progressPercent) {}

  public record KrakenScoreFactorResponse(
      @Schema(description = "요소 코드(열린 값 — 단계마다 늘어남, 모르는 코드는 그 줄만 따로 표시)", example = "ATTACK")
          String code,
      @Schema(description = "요소 배수. 계산 못 했으면 null", example = "1.0523") @NullableResponse
          BigDecimal value,
      @Schema(description = "OK·UNSUPPORTED", example = "OK") String status,
      @Schema(description = "UNSUPPORTED 이유 코드", example = "FACTOR_PENDING") @NullableResponse
          String reasonCode) {}

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
      @Schema(description = "UNSUPPORTED 이유 코드(시뮬레이터·스펙업과 같은 코드). OK면 null") @NullableResponse
          String reasonCode,
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
