package com.loakraken.specup.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import com.loakraken.common.api.ResponseContracts;
import com.loakraken.common.api.SuccessCode;
import com.loakraken.specup.application.SpecUpDetailService;
import com.loakraken.specup.presentation.SpecUpController.CandidateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 스펙업 후보 줄 상세(#544). 스펙업 가이드와 같은 플래그·같은 캐시를 쓰고 공식 호출은 없다. */
@RestController
@Validated
@Tag(name = "캐릭터", description = "캐릭터 검색 및 전투정보 조회")
public class SpecUpDetailController {
  /** 후보 id 모양(목록 후보 id와 같아야 한다 — 목록에서 받은 id로 줄 상세를 부른다). */
  public static final String CANDIDATE_ID = "[A-Z_]{1,40}(:[\\p{L}\\p{N} ]{1,30}){0,2}";

  private final SpecUpDetailService details;

  public SpecUpDetailController(SpecUpDetailService details) {
    this.details = details;
  }

  @GetMapping(
      value = "/api/v1/characters/spec-up/candidates/{candidateId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "스펙업 후보 줄 상세(공식 호출 없음)",
      description =
          "스펙업 가이드 목록의 후보 하나를 자세히 줍니다. 목록과 같은 캐릭터 캐시·시세 캐시로 목록을 다시 만들어 찾으므로 candidate는 목록의 같은 id 줄과 같습니다. "
              + "보석·각인서 후보는 market(사는 품목 BUY, 지금 보석 최저가를 비용에서 뺐으면 SELL 줄도 — 보석 비용(cost.amount) = BUY price − SELL price이고 sellFeeGold는 이 합계에 넣지 않습니다. 판매 수수료를 넣은 실제 부담 = cost.amount + SELL sellFeeGold), 일반 재련 후보는 refine·materials·goldFee를 줍니다. "
              + "재련 비용(cost.amount)은 materials의 반올림 전 금액 합 + goldFee를 올림한 값입니다. 표시 값(소수 둘째 자리)의 합을 올림한 값과 cost.amount의 차이는 1골드 이하입니다. "
              + "stats는 근거가 확인된 스탯만 담고(지금은 보석 기본 공격력 %), 없으면 빈 목록입니다. 해당 없는 칸은 null입니다. "
              + "요청이 많으면 429 CLIENT_RATE_LIMITED와 Retry-After(초)를 줍니다. "
              + "role·priceStatus·priceBasis·breath는 문자열이며 값이 늘 수 있습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "SPEC_UP_DETAIL_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST: 캐릭터명·후보 id 형식",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description =
            "SPEC_UP_CANDIDATE_NOT_FOUND: 지금 목록에 그 후보가 없거나, 시세가 연달아 바뀌어 목록 비용과 내역 금액을 맞추지 못함(목록을 다시 부름) · SPEC_UP_DISABLED: 기능이 꺼져 있음 · CHARACTER_NOT_CACHED: 캐릭터를 먼저 검색해야 함",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description = "CLIENT_RATE_LIMITED: 요청이 많음(Retry-After 초)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "CHARACTER_STORAGE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<SpecUpDetailResponse> detail(
      @Parameter(description = "스펙업 가이드 후보 id", example = "GEM_LEVEL:3:8")
          @PathVariable
          @Size(min = 1, max = 80)
          @Pattern(regexp = CANDIDATE_ID)
          String candidateId,
      @Parameter(description = "캐릭터명", required = true)
          @RequestParam
          @Size(min = 1, max = 16)
          @Pattern(regexp = "\\s*[\\p{L}\\p{N}]+\\s*")
          String characterName,
      @Parameter(
              description =
                  "목록과 같은 작열(쿨) 보석 포함 여부. false면 작열 후보는 404(목록 cooldownGemsIncluded가 false였다면 false로). 주지 않으면 모든 후보에서 찾음")
          @RequestParam(required = false)
          Boolean includeCooldownGems) {
    return ApiResponse.of(
        SpecUpDetailSuccessCode.SPEC_UP_DETAIL_SUCCESS,
        SpecUpDetailResponse.from(details.detail(characterName, candidateId, includeCooldownGems)));
  }

  enum SpecUpDetailSuccessCode implements SuccessCode {
    SPEC_UP_DETAIL_SUCCESS("스펙업 후보 상세 조회에 성공했습니다.");

    private final String message;

    SpecUpDetailSuccessCode(String message) {
      this.message = message;
    }

    public HttpStatus httpStatus() {
      return HttpStatus.OK;
    }

    public String code() {
      return name();
    }

    public String message() {
      return message;
    }
  }

  public record SpecUpDetailResponse(
      String characterName,
      @Schema(description = "캐릭터 캐시 수집 시각") Instant fetchedAt,
      @Schema(description = "목록 비용에 쓴 시세 칸 가운데 가장 오래된 관측 시각(목록 pricesAt과 같음, 쓴 칸이 없으면 null)")
          @NullableResponse
          Instant pricesAt,
      @Schema(description = "목록의 같은 id 후보 줄") CandidateResponse candidate,
      @Schema(description = "지금 공식 전투력(소수 둘째 자리). 없으면 null") @NullableResponse
          BigDecimal combatPowerBefore,
      @Schema(description = "이 후보를 적용한 전투력(= 전 + Δ, 시뮬레이터). UNSUPPORTED면 null") @NullableResponse
          BigDecimal combatPowerAfter,
      @Schema(description = "바뀌는 스탯 가운데 근거가 확인된 것(없으면 빈 목록)") List<SpecUpStatResponse> stats,
      @Schema(description = "일반 재련 후보만: 기대 재료 내역(0개 재료는 뺌). 그 밖은 null") @NullableResponse
          List<SpecUpMaterialResponse> materials,
      @Schema(description = "일반 재련 후보만: 기대 재련 골드(소수 둘째 자리). 그 밖은 null", example = "18432.50")
          @NullableResponse
          BigDecimal goldFee,
      @Schema(description = "보석·유물 각인서 후보만: 사거나 파는 품목 시세. 시세 칸을 아직 받지 못했거나 그 밖의 후보면 null")
          @NullableResponse
          List<SpecUpMarketResponse> market,
      @Schema(description = "일반 재련 후보만(재련 계획 링크 표시 신호). 그 밖은 null") @NullableResponse
          SpecUpRefineResponse refine) {
    static SpecUpDetailResponse from(SpecUpDetailService.Detail detail) {
      return new SpecUpDetailResponse(
          detail.characterName(),
          detail.fetchedAt(),
          detail.pricesAt(),
          CandidateResponse.from(detail.candidate()),
          detail.combatPowerBefore(),
          detail.combatPowerAfter(),
          detail.stats().stream().map(SpecUpStatResponse::from).toList(),
          detail.materials() == null
              ? null
              : detail.materials().stream().map(SpecUpMaterialResponse::from).toList(),
          detail.goldFee(),
          detail.market() == null
              ? null
              : detail.market().stream().map(SpecUpMarketResponse::from).toList(),
          SpecUpRefineResponse.from(detail.refine()));
    }
  }

  public record SpecUpStatResponse(
      @Schema(description = "스탯 이름(화면 문자열)", example = "기본 공격력") String name,
      @Schema(example = "0.60") BigDecimal before,
      @Schema(example = "0.80") BigDecimal after,
      @Schema(description = "단위(%, 없으면 null)", example = "%") @NullableResponse String unit) {
    static SpecUpStatResponse from(SpecUpDetailService.Stat stat) {
      return new SpecUpStatResponse(stat.name(), stat.before(), stat.after(), stat.unit());
    }
  }

  public record SpecUpMaterialResponse(
      @Schema(description = "공식 거래소 품목 이름", example = "운명의 파괴석") String itemName,
      @Schema(description = "품목 이미지 URL. 지금은 늘 null(웹은 자리표시)") @NullableResponse String icon,
      @Schema(description = "기대 개수(소수 둘째 자리)", example = "1250.40") BigDecimal quantity,
      @Schema(description = "1개 즉시 구매 최저가(골드, 소수 둘째 자리). 시세가 없으면 null", example = "0.85")
          @NullableResponse
          BigDecimal unitPrice,
      @Schema(description = "기대 개수 × 1개 값(골드, 소수 둘째 자리). 시세가 없으면 null", example = "1062.84")
          @NullableResponse
          BigDecimal gold,
      @Schema(description = "OK 또는 PENDING(문자열, 값이 늘 수 있음)", example = "OK") String priceStatus,
      @Schema(
              description =
                  "단가 근거 코드(문자열, 값이 늘 수 있음). MARKET_MIN_UNIT = 거래소 즉시 구매 최저가 ÷ 묶음 개수. 시세가 없으면 null",
              example = "MARKET_MIN_UNIT")
          @NullableResponse
          String priceBasis) {
    static SpecUpMaterialResponse from(SpecUpDetailService.Material material) {
      return new SpecUpMaterialResponse(
          material.itemName(),
          material.icon(),
          material.quantity(),
          material.unitPrice(),
          material.gold(),
          material.priceStatus(),
          material.priceBasis());
    }
  }

  public record SpecUpMarketResponse(
      @Schema(
              description = "BUY(사는 품목) 또는 SELL(지금 보석, 최저가를 비용에서 뺌). 문자열, 값이 늘 수 있음",
              example = "BUY")
          String role,
      @Schema(description = "공식 경매장·거래소 품목 이름", example = "8레벨 겁화의 보석") String itemName,
      @Schema(description = "품목 이미지 URL(각인서는 시세 캐시의 공식 아이콘, 보석은 null)") @NullableResponse
          String icon,
      @Schema(description = "공식 등급 문자열(모르면 null)") @NullableResponse String grade,
      @Schema(description = "1개 즉시 구매 최저가(골드)", example = "412000") long price,
      @Schema(description = "필요한 개수(보석 1, 유물 각인서 5 × 올리는 레벨 수)", example = "1") int quantity,
      @Schema(description = "SELL만: 판매 기대가 = 최저가 − 거래소 수수료 5%(1골드 미만 올림). 비용 계산에는 쓰지 않음(참고 값)")
          @NullableResponse
          Long expectedSalePrice,
      @Schema(
              description =
                  "SELL만: 지금 보석을 팔 때 거래소 수수료(판매 금액의 5%, 1골드 미만 올림). 비용(cost.amount)에는 넣지 않으며, 실제 부담 = 비용 + 이 값",
              example = "20000")
          @NullableResponse
          Long sellFeeGold,
      @Schema(description = "매물 수(보석만, 모르면 null)") @NullableResponse Long listedCount,
      @Schema(description = "시세 관측 시각") Instant observedAt) {
    static SpecUpMarketResponse from(SpecUpDetailService.Market market) {
      return new SpecUpMarketResponse(
          market.role(),
          market.itemName(),
          market.icon(),
          market.grade(),
          market.price(),
          market.quantity(),
          market.expectedSalePrice(),
          market.sellFeeGold(),
          market.listedCount(),
          market.observedAt());
    }
  }

  public record SpecUpRefineResponse(
      @Schema(example = "19") int fromStage,
      @Schema(example = "20") int toStage,
      @Schema(
              description =
                  "비용에 쓴 숨결 사용: NONE(쓰지 않음) 또는 OPTIMAL(시도마다 숨결 개수를 골라 씀, 기대 비용 최소)(문자열, 값이 늘 수 있음). 재련 계획이 다루지 않으면 null",
              example = "NONE")
          @NullableResponse
          String breath,
      @Schema(description = "기대 시도 횟수(소수 둘째 자리). 여러 단계 줄이면 단계마다 기대 시도의 합", example = "7.42")
          @NullableResponse
          BigDecimal expectedAttempts,
      @Schema(description = "첫 시도 성공 확률(만분율). 여러 단계 줄이면 첫 단계의 확률", example = "500")
          @NullableResponse
          Integer firstAttemptSuccessRateBp) {
    static SpecUpRefineResponse from(SpecUpDetailService.Refine refine) {
      return refine == null
          ? null
          : new SpecUpRefineResponse(
              refine.fromStage(),
              refine.toStage(),
              refine.breath(),
              refine.expectedAttempts(),
              refine.firstAttemptSuccessRateBp());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class SpecUpDetailOpenApiConfiguration {
    @Bean
    OpenApiCustomizer specUpDetailResponseContract() {
      return document -> {
        var schemas = document.getComponents().getSchemas();
        ResponseContracts.apply(SpecUpDetailResponse.class, schemas);
        ResponseContracts.apply(SpecUpStatResponse.class, schemas);
        ResponseContracts.apply(SpecUpMaterialResponse.class, schemas);
        ResponseContracts.apply(SpecUpMarketResponse.class, schemas);
        ResponseContracts.apply(SpecUpRefineResponse.class, schemas);
      };
    }
  }
}
