package com.loakraken.specup.presentation;

import com.loakraken.character.application.CombatPowerSimulationService;
import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import com.loakraken.common.api.ResponseContracts;
import com.loakraken.common.api.SuccessCode;
import com.loakraken.specup.application.SpecUpGoalService;
import com.loakraken.specup.application.SpecUpService;
import com.loakraken.specup.domain.SpecUpCost;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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
  private final SpecUpGoalService goals;

  public SpecUpController(SpecUpService specUp, SpecUpGoalService goals) {
    this.specUp = specUp;
    this.goals = goals;
  }

  @GetMapping(value = "/api/v1/characters/spec-up", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "스펙업 가이드(공식 호출 없음)",
      description =
          "캐릭터 캐시의 현재 세팅에서 후보 업그레이드마다 전투력 시뮬레이터를 변화 하나로 돌린 Δ전투력, 시세 캐시로 구한 비용, 1만 골드당 전투력을 줍니다. 후보끼리 Δ를 더하지 않습니다. "
              + "계산식이 확정되지 않은 후보도 status=UNSUPPORTED와 reasonCode·reason(같은 코드는 같은 문장)으로 남깁니다. "
              + "limit은 OK 후보에만 적용하고(total = 자르기 전 OK 후보 수), UNSUPPORTED는 모두 뒤에 붙습니다. "
              + "cost.status: OK(금액 있음)·PENDING(시세 칸을 아직 받지 못함)·UNKNOWN(비용 근거 없음). 보석 비용은 목표 레벨 즉시 구매 최저가 − 지금 보석 즉시 구매 최저가(판매 수수료는 빼지 않음, 귀속이면 빼지 않음)입니다. "
              + "sort=KRAKEN이면 후보마다 크라켄 점수 엔진으로 Δ크라켄 점수(적용 뒤 점수 − 지금 점수, 반올림 전 값의 차를 소수 둘째 자리로)를 내고 10만 골드당 점수 내림차순으로 정렬합니다(비용 모름은 Δ 순, 그 밖은 효율 순). "
              + "점수를 낼 수 없으면(서포터·점수 꺼짐·입력 없음) 효율 순으로 정렬하고 krakenSortUnavailableReason에 이유를 줍니다. "
              + "includeCooldownGems를 주지 않으면 KRAKEN은 작열(쿨) 보석 후보를 빼고 다른 정렬은 넣으며, 실제로 적용한 값은 cooldownGemsIncluded입니다. "
              + "kind·status·cost.status·reasonCode·sort·gemType·krakenSortUnavailableReason은 문자열이며 값이 늘 수 있습니다.")
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
      @Parameter(description = "EFFICIENCY(기본, 1만 골드당 전투력), DELTA(Δ전투력) 또는 KRAKEN(10만 골드당 Δ크라켄 점수)")
          @RequestParam(required = false)
          @Pattern(regexp = "EFFICIENCY|DELTA|KRAKEN")
          String sort,
      @Parameter(description = "순위 후보 최대 개수(기본 20, 최대 200 — 연마·스톤·카르마 줄까지 모두 보려면 크게)")
          @RequestParam(required = false)
          @Min(1)
          @Max(200)
          Integer limit,
      @Parameter(description = "작열(쿨) 보석 후보를 넣을지. 주지 않으면 KRAKEN은 빼고 다른 정렬은 넣음")
          @RequestParam(required = false)
          Boolean includeCooldownGems) {
    var guide =
        specUp.guide(
            characterName,
            sort == null ? SpecUpService.Sort.EFFICIENCY : SpecUpService.Sort.valueOf(sort),
            limit == null ? 20 : limit,
            includeCooldownGems);
    return ApiResponse.of(SpecUpSuccessCode.SPEC_UP_SUCCESS, SpecUpResponse.from(guide));
  }

  @GetMapping(
      value = "/api/v1/characters/spec-up/goal",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "스펙업 목표 경로(공식 호출 없음)",
      description =
          "목표 전투력에 닿을 때까지 지금 상태에서 항목마다 다음 한 단계를 만들고, 효율 순(1만 골드당 전투력, 비용을 모르는 단계는 그 뒤에 Δ전투력 순)으로 가장 앞의 단계를 적용하기를 되풀이한 경로를 줍니다. "
              + "같은 항목을 여러 단계 올릴 수 있습니다. 모든 전투력은 전투력 시뮬레이터 결과입니다(steps[].change를 차례로 시뮬레이터에 넣은 값과 같음). "
              + "금액(cost.amount·cumulativeCost·totalCost)은 모두 골드입니다. 합계에 들어가는 값 가운데 모르는 것이 있으면 cumulativeCost와 totalCost는 null입니다(0으로 더하지 않음 — 같은 보석 칸의 다음 단계 비용을 알면 그 칸 값은 다음 단계 것으로 바뀌어 다시 값이 생길 수 있음). "
              + "보석 단계의 cost는 지금에서 그 레벨까지의 누적 비용(스펙업 목록의 같은 목표 후보 비용과 같은 식, 음수 없음)이고, 같은 칸을 이어서 올리면 cumulativeCost·totalCost에는 그 칸의 마지막 단계 누적만 들어갑니다(단계 비용을 더하지 않음). 단계마다 따로 구해 그 레벨 시세가 없으면 그 단계만 비용을 모릅니다. "
              + "딜러가 아니거나 공식 전투력이 없으면 200, 빈 steps, stopReasonCode에 스펙업 가이드와 같은 이유 코드를 줍니다. "
              + "최대 30단계, 계산 시간 상한이 있습니다. 같은 캐릭터 수집 시각·시세 시각·목표는 10분 동안 캐시합니다(시간 상한으로 멈춘 결과는 캐시하지 않음). "
              + "요청이 많으면 429 CLIENT_RATE_LIMITED와 Retry-After(초)를 줍니다. 503은 저장소 장애이고 Retry-After가 없으니 잠시 뒤 다시 요청합니다. "
              + "stopReasonCode·kind·cost.code는 문자열이며 값이 늘 수 있습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "SPEC_UP_GOAL_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description =
            "INVALID_REQUEST: 캐릭터명·목표 형식(0 초과, 소수 둘째 자리까지) · SPEC_UP_GOAL_OUT_OF_RANGE: 목표가 지금 전투력의 2배 초과",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "SPEC_UP_DISABLED: 기능이 꺼져 있음 · CHARACTER_NOT_CACHED: 캐릭터를 먼저 검색해야 함",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "CHARACTER_STORAGE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<SpecUpGoalResponse> goal(
      @Parameter(description = "캐릭터명", required = true)
          @RequestParam
          @Size(min = 1, max = 16)
          @Pattern(regexp = "\\s*[\\p{L}\\p{N}]+\\s*")
          String characterName,
      @Parameter(description = "목표 전투력(0 초과, 소수 둘째 자리까지, 지금 전투력의 2배 이하)", required = true)
          @RequestParam
          @DecimalMin(value = "0", inclusive = false)
          @Digits(integer = 9, fraction = 2)
          BigDecimal targetCombatPower) {
    return ApiResponse.of(
        SpecUpSuccessCode.SPEC_UP_GOAL_SUCCESS,
        SpecUpGoalResponse.from(goals.goal(characterName, targetCombatPower)));
  }

  enum SpecUpSuccessCode implements SuccessCode {
    SPEC_UP_SUCCESS("스펙업 가이드 조회에 성공했습니다."),
    SPEC_UP_GOAL_SUCCESS("스펙업 목표 경로 조회에 성공했습니다.");

    private final String message;

    SpecUpSuccessCode(String message) {
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

  public record SpecUpGoalResponse(
      String characterName,
      @NullableResponse String serverName,
      @NullableResponse String className,
      @Schema(description = "지금 공식 전투력(소수 둘째 자리). 없으면 null") @NullableResponse
          BigDecimal combatPower,
      @Schema(description = "요청한 목표 전투력(소수 둘째 자리)") BigDecimal targetCombatPower,
      @Schema(description = "경로 끝 전투력(시뮬레이터). 계산하지 못하면 null") @NullableResponse
          BigDecimal finalCombatPower,
      @Schema(description = "경로 끝 전투력이 목표 이상인가") boolean reached,
      @Schema(
              description =
                  "멈춘 이유 코드(문자열, 값이 늘 수 있음): REACHED·ALREADY_REACHED·NO_CANDIDATE·STEP_LIMIT·TIME_LIMIT, 또는 스펙업 가이드의 이유 코드(ROLE_UNKNOWN 등)",
              example = "REACHED")
          String stopReasonCode,
      @Schema(description = "화면용 멈춘 이유 문장(같은 코드는 같은 문장)") String stopReason,
      @Schema(description = "경로 전체 비용(골드). 시세가 없는 단계가 있거나 계산하지 않았으면 null") @NullableResponse
          Long totalCost,
      @Schema(description = "비용(시세)을 모르는 단계 수") int unpricedSteps,
      @Schema(example = "spec-up-goal-v1") String ruleVersion,
      @Schema(description = "전투력을 낸 시뮬레이터 규칙", example = "combat-power-sim-v8")
          String simulatorRuleVersion,
      @Schema(description = "캐릭터 캐시 수집 시각") Instant fetchedAt,
      @Schema(description = "비용에 쓴 시세 칸 가운데 가장 오래된 관측 시각(쓴 칸이 없으면 null)") @NullableResponse
          Instant pricesAt,
      @Schema(description = "효율 순으로 목표에 닿을 때까지 고른 단계(순서대로)") List<SpecUpGoalStepResponse> steps) {
    static SpecUpGoalResponse from(SpecUpGoalService.Goal goal) {
      return new SpecUpGoalResponse(
          goal.characterName(),
          goal.serverName(),
          goal.className(),
          goal.combatPower(),
          goal.targetCombatPower(),
          goal.finalCombatPower(),
          goal.reached(),
          goal.stopReasonCode(),
          goal.stopReason(),
          goal.totalCost(),
          goal.unpricedSteps(),
          goal.ruleVersion(),
          goal.simulatorRuleVersion(),
          goal.fetchedAt(),
          goal.pricesAt(),
          goal.steps().stream().map(SpecUpGoalStepResponse::from).toList());
    }
  }

  public record SpecUpGoalStepResponse(
      @Schema(description = "1부터 시작하는 순서") int order,
      @Schema(description = "스펙업 가이드 후보 id와 같은 형식", example = "GEM_LEVEL:3:8") String candidateId,
      @Schema(
              description =
                  "단계 종류(문자열, 값이 늘 수 있음). 지금 값: GEM_LEVEL·EQUIPMENT_REFINE·EQUIPMENT_ADVANCED_REFINE·EQUIPMENT_QUALITY·ENGRAVING_LEVEL. 예정: POLISH(장신구 연마 교체), EQUIPMENT_SUCCESSION(장비 계승, 나중에 추가), 같은 칸 연속 재련은 EQUIPMENT_REFINE 그대로. 모르는 값은 target·from·to로 일반 줄로 다룬다.",
              example = "GEM_LEVEL")
          String kind,
      @Schema(description = "화면에 그대로 쓰는 대상 이름") String target,
      @Schema(description = "이 단계 전(화면 문자열)", example = "7레벨") String from,
      @Schema(description = "이 단계 뒤(화면 문자열)", example = "8레벨") String to,
      @Schema(description = "공식 아이템 이미지 URL") @NullableResponse String icon,
      @Schema(description = "공식 등급 문자열") @NullableResponse String grade,
      @Schema(description = "보석이 붙은 스킬 이름(보석 단계만)") @NullableResponse String skillName,
      @Schema(description = "전투력 시뮬레이터 요청의 changes[] 하나와 같은 모양") ChangeResponse change,
      @Schema(description = "이 단계로 늘어난 전투력(소수 둘째 자리)") BigDecimal deltaCombatPower,
      @Schema(description = "이 단계까지 적용한 전투력(시뮬레이터)") BigDecimal combatPowerAfter,
      @Schema(description = "이 단계 비용(골드). 보석 단계는 지금에서 그 레벨까지의 누적 비용(음수 없음)") CostResponse cost,
      @Schema(
              description =
                  "이 단계까지 경로 합계(골드, 보석 칸은 그 칸의 마지막 단계 누적만). 합계에 들어가는 값 가운데 모르는 것이 있으면 null")
          @NullableResponse
          Long cumulativeCost,
      @Schema(
              description =
                  "1만 골드당 전투력(소수 둘째 자리) = 이 단계 Δ ÷ 이 단계에 더 드는 비용(보석은 누적 차이). 더 드는 비용이 0 이하이거나 모르면 null")
          @NullableResponse
          BigDecimal combatPowerPer10kGold) {
    static SpecUpGoalStepResponse from(SpecUpGoalService.Step step) {
      return new SpecUpGoalStepResponse(
          step.order(),
          step.candidateId(),
          step.kind(),
          step.target(),
          step.from(),
          step.to(),
          step.icon(),
          step.grade(),
          step.skillName(),
          ChangeResponse.from(step.change()),
          step.deltaCombatPower(),
          step.combatPowerAfter(),
          CostResponse.from(step.cost()),
          step.cumulativeCost(),
          step.combatPowerPer10kGold());
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
      @Schema(
              description =
                  "실제로 쓴 정렬(문자열, 값이 늘 수 있음): EFFICIENCY·DELTA·KRAKEN. KRAKEN을 요청해도 점수를 낼 수 없으면 EFFICIENCY",
              example = "EFFICIENCY")
          String sort,
      int limit,
      @Schema(description = "limit으로 자르기 전 순위 후보 수(KRAKEN은 Δ크라켄 점수가 있는 후보 포함)") int total,
      @Schema(description = "순위 후보(정렬·limit) 뒤에 나머지 후보 전체") List<CandidateResponse> candidates,
      @Schema(description = "지금 크라켄 점수(소수 둘째 자리). 낼 수 없으면 null") @NullableResponse
          BigDecimal krakenScore,
      @Schema(description = "Δ크라켄 점수 규칙(낼 수 없으면 null)", example = "spec-up-kraken-v1")
          @NullableResponse
          String krakenRuleVersion,
      @Schema(description = "작열(쿨) 보석 후보를 넣었는지(요청 includeCooldownGems 또는 정렬 기본값)")
          boolean cooldownGemsIncluded,
      @Schema(
              description =
                  "Δ크라켄 점수를 낼 수 없는 이유(문자열, 값이 늘 수 있음): DISABLED·INPUT_PENDING·ROLE_UNKNOWN·SCORE_UNAVAILABLE(예전 SUPPORTER_PENDING은 더 내지 않음 — 서포터는 서포터 점수식으로 Δ를 낸다). 이때 KRAKEN도 효율 순. 낼 수 있으면 null",
              example = "INPUT_PENDING")
          @NullableResponse
          String krakenSortUnavailableReason) {
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
          guide.candidates().stream().map(CandidateResponse::from).toList(),
          guide.krakenScore(),
          guide.krakenRuleVersion(),
          guide.cooldownGemsIncluded(),
          guide.krakenSortUnavailableReason());
    }
  }

  public record CandidateResponse(
      @Schema(description = "같은 캐릭터·같은 후보면 늘 같은 값(목록 키)", example = "GEM_LEVEL:3:8") String id,
      @Schema(
              description =
                  "후보 종류(문자열, 값이 늘 수 있음). 시뮬레이터 kind와 같다. POLISH(장신구 연마 교체)·ABILITY_STONE(어빌리티 스톤)·KARMA_LEVEL(깨달음 카르마)은 크라켄 점수를 낼 수 있으면 후보마다 한 줄(change null·전투력 UNSUPPORTED·deltaKrakenScore 있음)이고, 그럴 수 없으면 종류마다 한 줄 UNSUPPORTED 알림",
              example = "GEM_LEVEL")
          String kind,
      @Schema(description = "화면에 그대로 쓰는 대상 이름", example = "블레이드 버스트 · 7레벨 겁화") String target,
      @Schema(description = "바꾸기 전(화면 문자열)", example = "7레벨") @NullableResponse String from,
      @Schema(description = "바꾼 뒤(화면 문자열)", example = "8레벨") @NullableResponse String to,
      @Schema(
              description =
                  "공식 아이템 이미지 URL(캐릭터 캐시). 각인 후보는 같은 이름 유물 각인서의 공식 아이콘(각인서 시세 캐시)이고, 캐시에 없으면 null")
          @NullableResponse
          String icon,
      @Schema(description = "공식 등급 문자열") @NullableResponse String grade,
      @Schema(description = "보석이 붙은 스킬 이름(보석 후보만)") @NullableResponse String skillName,
      @Schema(description = "권장 목표 단계(강화 후보용, to와 별개). 지금은 늘 null") @NullableResponse
          String suggestedTo,
      @Schema(
              description =
                  "전투력 시뮬레이터 요청의 changes[] 하나와 같은 모양. 한 줄 알림 종류와 점수 입력에서 만든 후보(POLISH·ABILITY_STONE·KARMA_LEVEL)는 null")
          @NullableResponse
          ChangeResponse change,
      @Schema(description = "OK 또는 UNSUPPORTED(문자열, 값이 늘 수 있음)", example = "OK") String status,
      @Schema(description = "Δ전투력(소수 둘째 자리). UNSUPPORTED면 null") @NullableResponse
          BigDecimal deltaCombatPower,
      @Schema(description = "Δ전투력 / 현재 전투력 × 100(소수 셋째 자리)") @NullableResponse
          BigDecimal deltaPercent,
      @Schema(
              description =
                  "비용. status가 UNSUPPORTED(전투력을 계산하지 못함)면 null이다. 다만 deltaKrakenScore가 있으면(크라켄 점수 기능이 켜졌을 때) 정렬과 상관없이 비용을 준다 — 줄 상세 candidate와 같은 값을 유지하기 위해")
          @NullableResponse
          CostResponse cost,
      @Schema(description = "1만 골드당 전투력(소수 둘째 자리). 비용이 OK일 때만") @NullableResponse
          BigDecimal combatPowerPer10kGold,
      @Schema(description = "UNSUPPORTED 이유 코드(문자열, 값이 늘 수 있음)", example = "MODEL_UNCONFIRMED")
          @NullableResponse
          String reasonCode,
      @Schema(description = "화면용 이유 문장(같은 코드는 같은 문장)") @NullableResponse String reason,
      @Schema(description = "시뮬레이터의 원래 문장(슬롯 번호·툴팁 원문이 들어가므로 같은 코드라도 문장이 후보마다 다름). 고정 문장과 같으면 null")
          @NullableResponse
          String reasonDetail,
      @Schema(
              description =
                  "보석 후보만: 빈 보석 칸에 새로 끼우는 후보면 true, 장착한 보석을 올리는 후보면 false. 그 밖의 후보는 null. 빈 칸은 어느 칸이든 값이 같아 가장 앞 빈 칸 하나로 6~10레벨을 낸다(from = 빈 칸, 겁화 기준 — 4티어 겁화·작열은 전투력 값이 같고 비용은 겁화 시세)")
          @NullableResponse
          Boolean emptySlot,
      @Schema(description = "보석 후보만: 바꾸기 전 그 칸 보석의 기본 공격력 %(빈 칸은 0). 모르면 null", example = "0.60")
          @NullableResponse
          BigDecimal baseAttackPctFrom,
      @Schema(
              description =
                  "보석 후보만: 바꾼 뒤 그 칸 보석의 기본 공격력 %(보석 계수표 6~10레벨 0.45·0.60·0.80·1.00·1.20 — 6·10레벨은 공식 보석 효과 확인값). 모르면 null",
              example = "0.80")
          @NullableResponse
          BigDecimal baseAttackPctTo,
      @Schema(
              description =
                  "Δ크라켄 점수 = 이 후보를 적용한 점수 − 지금 점수(반올림 전 값의 차를 소수 둘째 자리로 — 지금 점수 + Δ와 화면 최종 점수의 차이는 0.01 이하). 계산하지 못하면 null(빈 칸 보석·상급 재련·서포터 등). 장신구 연마 교체는 목표 장신구를 점수 옵션 두 줄(두 옵션이 모두 상이면 고정값 공격력·무기 공격력 셋째 줄 포함)만 있는 매물로 보고 계산한다",
              example = "98.81")
          @NullableResponse
          BigDecimal deltaKrakenScore,
      @Schema(
              description =
                  "10만 골드당 Δ크라켄 점수 = deltaKrakenScore ÷ cost.amount × 100,000(소수 둘째 자리, KRAKEN 정렬 키). Δ > 0이고 비용이 OK일 때만. 1만 골드당은 ÷ 10",
              example = "89.02")
          @NullableResponse
          BigDecimal krakenScorePer100kGold,
      @Schema(description = "보석 후보만: DAMAGE(딜) 또는 COOLDOWN(쿨, 작열), 문자열·값이 늘 수 있음. 그 밖 null")
          @NullableResponse
          String gemType) {
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
          candidate.reasonDetail(),
          candidate.emptySlot(),
          candidate.baseAttackPctFrom(),
          candidate.baseAttackPctTo(),
          candidate.deltaKrakenScore(),
          candidate.krakenScorePer100kGold(),
          candidate.gemType());
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
      @NullableResponse String name,
      @Schema(description = "팔 보호구 등급(ARM_GUARD만, 영웅·전설·유물·고대). 그 밖에는 null") @NullableResponse
          String grade) {
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
              change.name(),
              change.grade());
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
        ResponseContracts.apply(SpecUpGoalResponse.class, schemas);
        ResponseContracts.apply(SpecUpGoalStepResponse.class, schemas);
      };
    }
  }
}
