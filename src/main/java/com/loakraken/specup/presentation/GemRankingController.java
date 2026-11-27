package com.loakraken.specup.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import com.loakraken.common.api.SuccessCode;
import com.loakraken.specup.application.GemRankingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 아크 그리드 젬 교체 순위(M49 2단계, 계약 초안 'gem-ranking' 2026-11-22). 스위치
 * combat-power-sim.gem-ranking.enabled(기본 꺼짐, 꺼져 있으면 404). 캐릭터 캐시와 젬 순위 스냅숏(15분, 없으면 시세 보드 캐시의 아크
 * 그리드 탭 스캔)만 읽는다(공식 호출 없음). 스냅숏은 보드 방문과 젬 순위 예열(ArkGridGemWarmup, 스위치가 켜져 있을 때 10분마다)이 갱신한다.
 */
@RestController
@ConditionalOnProperty(
    prefix = "combat-power-sim.gem-ranking",
    name = "enabled",
    havingValue = "true")
@Tag(name = "캐릭터", description = "캐릭터 검색 및 전투정보 조회")
public class GemRankingController {
  private final GemRankingService service;

  public GemRankingController(GemRankingService service) {
    this.service = service;
  }

  @PostMapping(
      value = "/api/v1/characters/combat-power/gem-ranking",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "아크 그리드 젬 교체 순위(공식 호출 없음)",
      description =
          "딜러의 장착 젬 칸마다 그 코어 계열의 세 종류 × 효과 정책(ALL·A_OR_B·A·A_AND_B, A·B는 그 종류의 딜러 옵션 두 개) × 초기화로, 새 젬을 사서 '네 옵션 포인트 합이 targetTotal 이상이 되면 완료' 정책으로 가공했을 때 "
              + "이 칸에 넣어 전투력이 오르고 의지력이 맞고 정책 효과를 만족할 확률(successProbability, 0~1), 성공할 때의 기대 전투력 증가(expectedGainPct, %, 시뮬레이터 deltaPercent와 같은 단위), 기대 비용(구성 요소 모두 젬 여러 개를 산 전체 기대값이고 합이 total)을 줍니다. "
              + "targetTotal은 확률이 가장 큰 목표 합입니다. 성공 확률이 0인 줄은 주지 않습니다. 순위: goldPerPercent 오름차순(같으면 확률 높은 쪽), 비용이 없는 줄은 그 뒤에 expectedGainPct × successProbability 내림차순. "
              + "젬 가격은 거래소 시세 캐시(1개 즉시 구매 최저가)만 읽고, 페온 1개 골드·초기화권 가격은 요청 값입니다. 서포터·판정 불가는 status UNSUPPORTED와 빈 rows. "
              + "slotIndex·gemIndex는 공식 ArkGrid Slots·Gems Index(0부터)로 캐릭터 응답과 같습니다. policy·gemType·grade·missingPrices·status·reasonCode는 문자열이며 값이 늘 수 있습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "GEM_RANKING_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST: 등급·정책·초기화·가격·limit 값",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description =
            "CHARACTER_NOT_CACHED: 캐릭터를 먼저 검색해야 함. 스위치(combat-power-sim.gem-ranking.enabled)가 꺼져 있으면 이 경로가 없어 404",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description = "GEM_RANKING_BUSY: 순위 계산 자리가 모두 차 있음(기다리지 않음, Retry-After 초 뒤 다시)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<GemRankingResponse> rank(@Valid @RequestBody GemRankingRequest request) {
    var result =
        service.rank(
            new GemRankingService.Query(
                request.characterName(),
                request.grade(),
                request.policies() == null || request.policies().isEmpty()
                    ? new LinkedHashSet<>(List.of(GemRankingService.Policy.values()))
                    : new LinkedHashSet<>(request.policies()),
                request.reset() == null || request.reset().isEmpty()
                    ? new LinkedHashSet<>(List.of(false, true))
                    : new LinkedHashSet<>(request.reset()),
                request.prices() == null ? null : request.prices().resetTicketGold(),
                request.prices() == null ? null : request.prices().pheonGold(),
                request.limit() == null ? 50 : request.limit()));
    return ApiResponse.of(
        GemRankingSuccessCode.GEM_RANKING_SUCCESS, GemRankingResponse.from(result));
  }

  /** 응답 필수·null 허용 규칙(@NullableResponse)을 문서에 붙인다. 스위치가 꺼져 있으면 이 컨트롤러가 없어 문서에도 없다. */
  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(
      prefix = "combat-power-sim.gem-ranking",
      name = "enabled",
      havingValue = "true")
  static class GemRankingOpenApiConfiguration {
    @org.springframework.context.annotation.Bean
    org.springdoc.core.customizers.OpenApiCustomizer gemRankingResponseContract() {
      return document ->
          com.loakraken.common.api.ResponseContracts.apply(
              GemRankingResponse.class, document.getComponents().getSchemas());
    }
  }

  enum GemRankingSuccessCode implements SuccessCode {
    GEM_RANKING_SUCCESS;

    public HttpStatus httpStatus() {
      return HttpStatus.OK;
    }

    public String code() {
      return name();
    }

    public String message() {
      return "젬 교체 순위 계산에 성공했습니다.";
    }
  }

  public record GemRankingRequest(
      @NotBlank @Size(max = 32) @Schema(description = "캐릭터명(먼저 검색해 캐시에 있어야 함)", example = "모험가")
          String characterName,
      @NotNull @Schema(description = "HERO(영웅)·RARE(희귀). 고급은 나중", example = "HERO")
          GemRankingService.Grade grade,
      @Size(max = 4) @Schema(description = "정책(생략하면 네 가지 모두). 같은 값을 두 번 쓰면 400")
          List<GemRankingService.Policy> policies,
      @Size(max = 2)
          @Schema(
              description =
                  "초기화 끔·켬(생략하면 둘 다). 켬(true)은 prices.resetTicketGold가 있을 때만 계산(없으면 그 줄은 빠짐)")
          List<Boolean> reset,
      @Valid GemRankingPrices prices,
      @Min(1) @Max(200) @Schema(description = "돌려줄 줄 수(1~200, 생략하면 50)") Integer limit) {
    @AssertTrue(message = "policies에 같은 값이 두 번 있습니다.")
    @Schema(hidden = true)
    public boolean isPoliciesDistinct() {
      return policies == null || policies.stream().distinct().count() == policies.size();
    }

    @AssertTrue(message = "reset에 같은 값이 두 번 있거나 빈 값이 있습니다.")
    @Schema(hidden = true)
    public boolean isResetDistinct() {
      return reset == null
          || (reset.stream().noneMatch(java.util.Objects::isNull)
              && reset.stream().distinct().count() == reset.size());
    }
  }

  @Schema(description = "사용자 가격(서버가 시세에서 읽지 않는 값)")
  public record GemRankingPrices(
      @Min(0) @Max(10_000_000) @Schema(description = "젬 가공 초기화권 1장 골드(사용자 입력)", example = "59110")
          Long resetTicketGold,
      @DecimalMin("0")
          @DecimalMax("100000")
          @Schema(description = "페온 1개 골드(사용자의 크리스탈 환율로 정한 값)", example = "72.5")
          BigDecimal pheonGold) {}

  public record GemRankingResponse(
      String ruleVersion,
      String characterName,
      @Schema(description = "캐릭터 캐시 수집 시각") Instant fetchedAt,
      @Schema(description = "OK·UNSUPPORTED(문자열, 값이 늘 수 있음)", example = "OK") String status,
      @Schema(description = "UNSUPPORTED 이유 코드(시뮬레이터와 같은 코드). OK면 null") @NullableResponse
          String reasonCode,
      @Schema(description = "UNSUPPORTED 이유 문장. OK면 null") @NullableResponse String basis,
      @Schema(description = "젬 시세 관측 시각. 시세 캐시가 없으면 null") @NullableResponse
          Instant pricesObservedAt,
      @Schema(description = "limit 전 줄 수") int totalRows,
      List<GemRankingRowResponse> rows,
      @Schema(description = "계산 가정과 표시 문구") List<String> assumptions) {
    static GemRankingResponse from(GemRankingService.Result result) {
      return new GemRankingResponse(
          result.ruleVersion(),
          result.characterName(),
          result.fetchedAt(),
          result.status(),
          result.reasonCode(),
          result.basis(),
          result.pricesObservedAt(),
          result.totalRows(),
          result.rows().stream().map(GemRankingRowResponse::from).toList(),
          result.assumptions());
    }
  }

  public record GemRankingRowResponse(
      @Schema(description = "순위(1부터)") int rank,
      @Schema(description = "공식 ArkGrid.Slots Index(0부터)") int slotIndex,
      @Schema(description = "공식 Gems Index(0부터)") int gemIndex,
      String coreName,
      GemRankingCurrentGemResponse currentGem,
      @Schema(description = "새 젬 종류(문자열)") String gemType,
      @Schema(description = "HERO·RARE(문자열)") String grade,
      @Schema(description = "ALL·A_OR_B·A·A_AND_B(문자열)") String policy,
      boolean reset,
      @Schema(description = "가공 목표 포인트 합 T*(이 합 이상이 되면 완료하는 정책, 확률이 가장 큰 T)") int targetTotal,
      @Schema(description = "젬 하나당 성공 확률(0~1, 소수 여섯째 자리)") BigDecimal successProbability,
      @Schema(description = "성공까지 기대 젬 수 = 1 / successProbability(소수 둘째 자리)")
          BigDecimal expectedGems,
      @Schema(description = "성공할 때 기대 전투력 증가(%, 소수 셋째 자리)") BigDecimal expectedGainPct,
      @Schema(description = "성공할 때 기대 옵션 레벨(소수 둘째 자리)")
          List<GemRankingOptionResponse> expectedOptions,
      @Schema(description = "성공할 때 기대 의지력 효율") BigDecimal expectedWillEfficiency,
      @Schema(description = "성공할 때 기대 질서·혼돈 포인트") BigDecimal expectedPoint,
      @Schema(description = "기대 비용(가격이 하나라도 없으면 null)") @NullableResponse
          GemRankingCostResponse expectedCost,
      @Schema(description = "1% 전투력당 골드 = expectedCost.total / expectedGainPct. 비용이 없으면 null")
          @NullableResponse
          BigDecimal goldPerPercent,
      @Schema(description = "없는 가격: GEM·PHEON·RESET_TICKET(문자열)") List<String> missingPrices) {
    static GemRankingRowResponse from(GemRankingService.Row row) {
      return new GemRankingRowResponse(
          row.rank(),
          row.slotIndex(),
          row.gemIndex(),
          row.coreName(),
          new GemRankingCurrentGemResponse(
              row.currentGem().type(),
              row.currentGem().point(),
              row.currentGem().effects().stream()
                  .map(e -> new GemRankingOptionResponse(e.name(), e.level()))
                  .toList()),
          row.gemType(),
          row.grade(),
          row.policy(),
          row.reset(),
          row.targetTotal(),
          row.successProbability(),
          row.expectedGems(),
          row.expectedGainPct(),
          row.expectedOptions().stream()
              .map(e -> new GemRankingOptionResponse(e.name(), e.level()))
              .toList(),
          row.expectedWillEfficiency(),
          row.expectedPoint(),
          row.expectedCost() == null
              ? null
              : new GemRankingCostResponse(
                  row.expectedCost().gem(),
                  row.expectedCost().pheon(),
                  row.expectedCost().processingGold(),
                  row.expectedCost().resetTicket(),
                  row.expectedCost().total()),
          row.goldPerPercent(),
          row.missingPrices());
    }
  }

  @Schema(description = "지금 그 칸의 젬")
  public record GemRankingCurrentGemResponse(
      @NullableResponse String type,
      @NullableResponse Integer point,
      List<GemRankingOptionResponse> effects) {}

  public record GemRankingOptionResponse(String name, BigDecimal level) {}

  @Schema(description = "기대 비용(골드). 모두 성공까지 젬 여러 개를 산 전체 기대값이고 합이 total")
  public record GemRankingCostResponse(
      @Schema(description = "젬 구매 = 젬 1개 값 × expectedGems") long gem,
      @Schema(description = "페온 = 구매 한 번의 페온(영웅 12·희귀 6) × 페온 1개 골드 × expectedGems") long pheon,
      @Schema(description = "가공 골드 = 젬 하나의 기대 가공 골드 × expectedGems") long processingGold,
      @Schema(description = "초기화권 = 젬 하나의 기대 사용 수 × expectedGems × 초기화권 가격") long resetTicket,
      long total) {}
}
