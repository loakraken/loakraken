package com.loakraken.specup.presentation;

import com.loakraken.character.application.CombatPowerSimulationService;
import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import com.loakraken.common.api.ResponseContracts;
import com.loakraken.common.api.SuccessCode;
import com.loakraken.specup.application.SpecUpService;
import com.loakraken.specup.domain.SpecUpCost;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 스펙업 가이드(#315). 플래그(spec-up.enabled, 기본 꺼짐)가 꺼져 있어도 경로는 있고 SPEC_UP_DISABLED(404)를 준다(PM-프론트 16:27
 * ⑦: 캐시 없음과 구분). 공식 호출은 없다.
 */
@RestController
@Validated
@Tag(name = "캐릭터", description = "캐릭터 검색 및 전투정보 조회")
public class SpecUpController {
  private final SpecUpService specUp;

  public SpecUpController(SpecUpService specUp) {
    this.specUp = specUp;
  }

  @GetMapping(value = "/api/v1/characters/spec-up", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "스펙업 가이드(공식 호출 없음)",
      description =
          "캐릭터 캐시의 현재 세팅에서 후보 업그레이드마다 전투력 시뮬레이터를 변화 하나로 돌린 Δ전투력, 시세 캐시로 구한 비용, 1만 골드당 전투력을 줍니다. 후보끼리 Δ를 더하지 않습니다. "
              + "계산식이 확정되지 않은 후보도 status=UNSUPPORTED와 reasonCode·reason(같은 코드는 같은 문장)으로 남깁니다. "
              + "limit은 OK 후보에만 적용하고(total = 자르기 전 OK 후보 수), UNSUPPORTED는 모두 뒤에 붙습니다. "
              + "cost.status: OK(금액 있음)·PENDING(시세 칸을 아직 받지 못함)·UNKNOWN(비용 근거 없음). 보석 비용은 목표 레벨 즉시 구매 최저가 − 지금 보석 판매 기대가(거래소 수수료 5% 올림, 귀속이면 빼지 않음)입니다. "
              + "kind·status·cost.status·reasonCode는 문자열이며 값이 늘 수 있습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "SPEC_UP_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST: 캐릭터명·sort·limit 형식",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description =
            "SPEC_UP_DISABLED: 기능이 꺼져 있음(메뉴 숨김) · CHARACTER_NOT_CACHED: 캐릭터를 먼저 검색해야 함(처리정지 목록 포함)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "CHARACTER_STORAGE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<SpecUpResponse> guide(
      @Parameter(description = "캐릭터명", required = true)
          @RequestParam
          @Size(min = 1, max = 16)
          @Pattern(regexp = "\\s*[\\p{L}\\p{N}]+\\s*")
          String characterName,
      @Parameter(description = "EFFICIENCY(기본, 1만 골드당 전투력) 또는 DELTA(Δ전투력)")
          @RequestParam(required = false)
          @Pattern(regexp = "EFFICIENCY|DELTA")
          String sort,
      @Parameter(description = "OK 후보 최대 개수(기본 20, 최대 50)")
          @RequestParam(required = false)
          @Min(1)
          @Max(50)
          Integer limit) {
    var guide =
        specUp.guide(
            characterName,
            sort == null ? SpecUpService.Sort.EFFICIENCY : SpecUpService.Sort.valueOf(sort),
            limit == null ? 20 : limit);
    return ApiResponse.of(SpecUpSuccessCode.SPEC_UP_SUCCESS, SpecUpResponse.from(guide));
  }

  enum SpecUpSuccessCode implements SuccessCode {
    SPEC_UP_SUCCESS;

    public HttpStatus httpStatus() {
      return HttpStatus.OK;
    }

    public String code() {
      return name();
    }

    public String message() {
      return "스펙업 가이드 조회에 성공했습니다.";
    }
  }

  public record SpecUpResponse(
      String characterName,
      @NullableResponse String serverName,
      @NullableResponse String className,
      @Schema(description = "공식 전투력(소수 둘째 자리). 없으면 null이고 모든 후보가 UNSUPPORTED") @NullableResponse
          BigDecimal combatPower,
      @Schema(example = "spec-up-v1") String ruleVersion,
      @Schema(description = "Δ전투력을 낸 시뮬레이터 규칙", example = "combat-power-sim-v8")
          String simulatorRuleVersion,
      @Schema(description = "캐릭터 캐시 수집 시각") Instant fetchedAt,
      @Schema(description = "비용에 쓴 시세 칸 가운데 가장 오래된 관측 시각(쓴 칸이 없으면 null)") @NullableResponse
          Instant pricesAt,
      @Schema(description = "EFFICIENCY 또는 DELTA(문자열)", example = "EFFICIENCY") String sort,
      int limit,
      @Schema(description = "limit으로 자르기 전 OK 후보 수") int total,
      @Schema(description = "OK 후보(정렬·limit) 뒤에 UNSUPPORTED 후보 전체")
          List<CandidateResponse> candidates) {
    static SpecUpResponse from(SpecUpService.Guide guide) {
      return new SpecUpResponse(
          guide.characterName(),
          guide.serverName(),
          guide.className(),
          guide.combatPower(),
          guide.ruleVersion(),
          guide.simulatorRuleVersion(),
          guide.fetchedAt(),
          guide.pricesAt(),
          guide.sort(),
          guide.limit(),
          guide.total(),
          guide.candidates().stream().map(CandidateResponse::from).toList());
    }
  }

  public record CandidateResponse(
      @Schema(description = "같은 캐릭터·같은 후보면 늘 같은 값(목록 키)", example = "GEM_LEVEL:3:8") String id,
      @Schema(
              description =
                  "후보 종류(문자열, 값이 늘 수 있음). 시뮬레이터 kind와 같고, 아직 계산하지 않는 종류(KARMA_LEVEL·ABILITY_STONE 등)는 한 줄 UNSUPPORTED로",
              example = "GEM_LEVEL")
          String kind,
      @Schema(description = "화면에 그대로 쓰는 대상 이름", example = "블레이드 버스트 · 7레벨 겁화") String target,
      @Schema(description = "바꾸기 전(화면 문자열)", example = "7레벨") @NullableResponse String from,
      @Schema(description = "바꾼 뒤(화면 문자열)", example = "8레벨") @NullableResponse String to,
      @Schema(description = "공식 아이템 이미지 URL(캐릭터 캐시)") @NullableResponse String icon,
      @Schema(description = "공식 등급 문자열") @NullableResponse String grade,
      @Schema(description = "보석이 붙은 스킬 이름(보석 후보만)") @NullableResponse String skillName,
      @Schema(description = "권장 목표 단계(강화 후보용, to와 별개). 지금은 늘 null") @NullableResponse
          String suggestedTo,
      @Schema(description = "전투력 시뮬레이터 요청의 changes[] 하나와 같은 모양. 한 줄 알림 종류는 null") @NullableResponse
          ChangeResponse change,
      @Schema(description = "OK 또는 UNSUPPORTED(문자열, 값이 늘 수 있음)", example = "OK") String status,
      @Schema(description = "Δ전투력(소수 둘째 자리). UNSUPPORTED면 null") @NullableResponse
          BigDecimal deltaCombatPower,
      @Schema(description = "Δ전투력 / 현재 전투력 × 100(소수 셋째 자리)") @NullableResponse
          BigDecimal deltaPercent,
      @Schema(description = "비용. UNSUPPORTED면 null") @NullableResponse CostResponse cost,
      @Schema(description = "1만 골드당 전투력(소수 둘째 자리). 비용이 OK일 때만") @NullableResponse
          BigDecimal combatPowerPer10kGold,
      @Schema(description = "UNSUPPORTED 이유 코드(문자열, 값이 늘 수 있음)", example = "MODEL_UNCONFIRMED")
          @NullableResponse
          String reasonCode,
      @Schema(description = "화면용 이유 문장(같은 코드는 같은 문장)") @NullableResponse String reason,
      @Schema(description = "시뮬레이터의 원래 문장(슬롯 번호·툴팁 원문이 들어가므로 같은 코드라도 문장이 후보마다 다름). 고정 문장과 같으면 null")
          @NullableResponse
          String reasonDetail) {
    static CandidateResponse from(SpecUpService.Candidate candidate) {
      return new CandidateResponse(
          candidate.id(),
          candidate.kind(),
          candidate.target(),
          candidate.from(),
          candidate.to(),
          candidate.icon(),
          candidate.grade(),
          candidate.skillName(),
          candidate.suggestedTo(),
          ChangeResponse.from(candidate.change()),
          candidate.status(),
          candidate.deltaCombatPower(),
          candidate.deltaPercent(),
          CostResponse.from(candidate.cost()),
          candidate.combatPowerPer10kGold(),
          candidate.reasonCode(),
          candidate.reason(),
          candidate.reasonDetail());
    }
  }

  public record CostResponse(
      @Schema(description = "금액 단위(문자열, 지금은 GOLD, 개수 단위가 들어올 수 있음)", example = "GOLD") String unit,
      @Schema(description = "금액(정수, unit 단위). OK일 때만") @NullableResponse Long amount,
      @Schema(description = "OK·PENDING·UNKNOWN(문자열, 모르는 값은 UNKNOWN으로)", example = "OK")
          String status,
      @Schema(description = "비용 근거 코드(문자열, 값이 늘 수 있음)", example = "GEM_UPGRADE") String code,
      @Schema(description = "화면용 비용 근거 문장(같은 코드는 같은 문장)") String basis) {
    static CostResponse from(SpecUpCost cost) {
      return cost == null
          ? null
          : new CostResponse(cost.unit(), cost.amount(), cost.status(), cost.code(), cost.basis());
    }
  }

  public record ChangeResponse(
      String kind,
      @NullableResponse Integer slot,
      @NullableResponse Integer index,
      @NullableResponse Integer level,
      @NullableResponse Integer refine,
      @NullableResponse Integer quality,
      @NullableResponse String name) {
    static ChangeResponse from(CombatPowerSimulationService.Change change) {
      return change == null
          ? null
          : new ChangeResponse(
              change.kind().name(),
              change.slot(),
              change.index(),
              change.level(),
              change.refine(),
              change.quality(),
              change.name());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class SpecUpOpenApiConfiguration {
    @Bean
    OpenApiCustomizer specUpResponseContract() {
      return document -> {
        var schemas = document.getComponents().getSchemas();
        ResponseContracts.apply(SpecUpResponse.class, schemas);
        ResponseContracts.apply(CandidateResponse.class, schemas);
        ResponseContracts.apply(CostResponse.class, schemas);
        ResponseContracts.apply(ChangeResponse.class, schemas);
      };
    }
  }
}
