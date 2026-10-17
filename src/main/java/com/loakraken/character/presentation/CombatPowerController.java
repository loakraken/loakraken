package com.loakraken.character.presentation;

import com.loakraken.character.application.CombatPowerSimulationService;
import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import com.loakraken.common.api.ResponseContracts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 전투력 시뮬레이터(계약 v0 A단계, #239). 계산식(Q1·Q2)이 항목별로 확정되기 전까지 운영에서 열지 않는다(combat-power-sim.enabled, 기본
 * false). 공식 호출은 없다.
 */
@ConditionalOnProperty(prefix = "combat-power-sim", name = "enabled", havingValue = "true")
@RestController
@Tag(name = "캐릭터", description = "캐릭터 검색 및 전투정보 조회")
public class CombatPowerController {
  private final CombatPowerSimulationService simulator;

  public CombatPowerController(CombatPowerSimulationService simulator) {
    this.simulator = simulator;
  }

  @GetMapping(
      value = "/api/v1/characters/combat-power/options",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "전투력 시뮬레이터 입력 범위",
      description = "화면 입력의 허용 범위입니다. verified=false면 숫자 범위가 공식 근거로 확정되기 전 예시입니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "CHARACTER_COMBAT_POWER_OPTIONS_SUCCESS")
  })
  public ApiResponse<CombatPowerOptionsResponse> options() {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_COMBAT_POWER_OPTIONS_SUCCESS,
        CombatPowerOptionsResponse.from(simulator.options()));
  }

  @PostMapping(
      value = "/api/v1/characters/combat-power/simulate",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "전투력 시뮬레이션(공식 호출 없음)",
      description =
          "캐릭터 캐시의 현재 세팅에 바꾼 항목(changes)을 순서대로 적용합니다. 같은 대상은 뒤의 변경이 이깁니다. "
              + "after는 바꾼 항목을 함께 적용한 값이고 contributions[].delta는 순서대로 하나씩 적용했을 때의 증가분입니다(합 = delta). "
              + "계산식이 확정되지 않은 항목은 status=UNSUPPORTED이고 delta=null이며 after에 들어가지 않습니다(partial=true). "
              + "반올림은 서버에서 끝냅니다(전투력 소수 둘째 자리, deltaPercent % 소수 셋째 자리).")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "CHARACTER_COMBAT_POWER_SIMULATE_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST: 범위 밖·모르는 kind·필수 값 없음(메시지에 필드와 허용 범위)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_CACHED: 캐릭터 캐시 없음(공식 호출 없음)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "CHANGE_TARGET_NOT_FOUND: 캐시가 바뀌어 바꿀 대상이 캐릭터에 없음(다시 불러오기)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "415",
        description = "UNSUPPORTED_MEDIA_TYPE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CombatPowerSimulationResponse> simulate(
      @Valid @RequestBody CombatPowerSimulationRequest request) {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_COMBAT_POWER_SIMULATE_SUCCESS,
        CombatPowerSimulationResponse.from(
            simulator.simulate(request.characterName(), request.toChanges())));
  }

  @GetMapping(
      value = "/api/v1/characters/combat-power/contributions",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "장착 항목의 전투력 기여(공식 호출 없음)",
      description =
          "그 항목을 뺐을 때 공식 전투력이 몇 % 줄어드는지입니다(전투력 기여 % = 1 − 뺀 전투력 / 지금 전투력). 지금은 팔찌(효과 줄 전부 + 팔찌 도약 포인트)만 주고, kind는 문자열이라 늘어날 수 있습니다. "
              + "계산할 수 없으면 status=UNSUPPORTED이고 contributionPct=null입니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "CHARACTER_COMBAT_POWER_CONTRIBUTIONS_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_CACHED: 캐릭터 캐시 없음(공식 호출 없음)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CombatPowerContributionsResponse> contributions(
      @org.springframework.web.bind.annotation.RequestParam
          @NotBlank
          @Size(max = 16)
          @Pattern(regexp = "\\s*[\\p{L}\\p{N}]+\\s*")
          String characterName) {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_COMBAT_POWER_CONTRIBUTIONS_SUCCESS,
        CombatPowerContributionsResponse.from(simulator.contributions(characterName)));
  }

  public record CombatPowerContributionsResponse(
      String characterName,
      Instant fetchedAt,
      @Schema(description = "공식 전투력(소수 둘째 자리)") @NullableResponse BigDecimal combatPower,
      String ruleVersion,
      List<ContributionItemResponse> items) {
    static CombatPowerContributionsResponse from(CombatPowerSimulationService.Contributions value) {
      return new CombatPowerContributionsResponse(
          value.characterName(),
          value.fetchedAt(),
          value.combatPower(),
          CombatPowerSimulationService.RULE_VERSION,
          value.items().stream()
              .map(
                  item ->
                      new ContributionItemResponse(
                          item.kind(),
                          item.target(),
                          item.status().name(),
                          item.contributionPct(),
                          item.withoutCombatPower(),
                          item.basis(),
                          item.reasonCode()))
              .toList());
    }
  }

  public record ContributionItemResponse(
      @Schema(description = "BRACELET 등(문자열, 값이 늘 수 있음)", example = "BRACELET") String kind,
      String target,
      @Schema(description = "OK 또는 UNSUPPORTED(문자열)", example = "OK") String status,
      @Schema(description = "전투력 기여 %(소수 둘째 자리). UNSUPPORTED면 null", example = "12.60")
          @NullableResponse
          BigDecimal contributionPct,
      @Schema(description = "그 항목을 뺐을 때 전투력(소수 둘째 자리)") @NullableResponse
          BigDecimal withoutCombatPower,
      @Schema(description = "계산 근거 또는 계산하지 않은 이유") String basis,
      @Schema(description = "UNSUPPORTED 이유 코드(시뮬레이터·스펙업과 같은 코드). OK면 null") @NullableResponse
          String reasonCode) {}

  public record CombatPowerSimulationRequest(
      @Schema(description = "캐릭터명", example = "모험가", requiredMode = Schema.RequiredMode.REQUIRED)
          @NotBlank
          @Size(max = 16)
          @Pattern(regexp = "\\s*[\\p{L}\\p{N}]+\\s*")
          String characterName,
      @Schema(description = "바꾼 항목(순서대로 적용, 최대 64개)") @NotNull @Size(max = 64)
          List<@Valid ChangeRequest> changes) {
    List<CombatPowerSimulationService.Change> toChanges() {
      return changes.stream().map(ChangeRequest::toChange).toList();
    }
  }

  @Schema(description = "바꾼 항목 하나. kind마다 쓰는 필드가 다르다")
  public record ChangeRequest(
      @NotNull CombatPowerSimulationService.Kind kind,
      @Schema(
              description =
                  "보석 slot(GEM_LEVEL, 0~10). 보석이 없는 빈 칸이면 그 칸에 4티어 보석을 새로 끼우는 변화로 계산한다(before = 0)")
          Integer slot,
      @Schema(description = "검색 응답 loadout.equipment 순번(EQUIPMENT_*·POLISH)") Integer index,
      @Schema(description = "연마·팔찌 효과 줄 번호(0부터)") Integer line,
      @Schema(description = "보석 레벨(0은 보석 빼기)·상급 재련 단계·각인·스톤 레벨") Integer level,
      @Schema(description = "재련 단계(EQUIPMENT_REFINE)") Integer refine,
      @Schema(description = "품질(EQUIPMENT_QUALITY)") Integer quality,
      @Schema(
              description =
                  "연마 옵션 이름(POLISH, options의 polishValues.options[].option). 같은 부위에 같은 이름의 %·고정값이 둘 다 있으면(귀걸이 공격력·무기 공격력) % 쪽은 끝에 \" %\"를 붙인다(예: \"공격력 %\").")
          @Size(max = 32)
          String option,
      @Schema(description = "LOW·MID·HIGH(POLISH·BRACELET_EFFECT), 코어 등급(ARK_GRID_CORE)")
          @Size(max = 8)
          String grade,
      @Schema(description = "팔찌 효과·각인·스톤 각인 이름") @Size(max = 32) String name,
      @Schema(description = "아크 패시브 트리(진화·깨달음·도약)") @Size(max = 8) String tree,
      @Schema(description = "아크 패시브 포인트") Integer points,
      @Schema(description = "아크 그리드 slot index") Integer slotIndex,
      @Schema(description = "아크 그리드 코어 포인트") Integer point) {
    CombatPowerSimulationService.Change toChange() {
      return new CombatPowerSimulationService.Change(
          kind, slot, index, line, level, refine, quality, option, grade, name, tree, points,
          slotIndex, point);
    }
  }

  public record CombatPowerSimulationResponse(
      @Schema(description = "계산 규칙 버전(문자열)") String ruleVersion,
      String characterName,
      @Schema(description = "현재 세팅을 읽은 캐릭터 캐시의 수집 시각(검색 응답 fetchedAt과 비교)") Instant fetchedAt,
      @Schema(description = "공식 전투력(armory CombatPower). 없으면 null") @NullableResponse
          BigDecimal before,
      @Schema(description = "현재 세팅의 모델값(검증용, 화면 표시 안 함). 계산식 확정 전 null") @NullableResponse
          BigDecimal modelBefore,
      @Schema(description = "바꾼 항목을 함께 적용한 전투력(계산된 항목만)") @NullableResponse BigDecimal after,
      @NullableResponse BigDecimal delta,
      @Schema(description = "% 단위, 소수 셋째 자리") @NullableResponse BigDecimal deltaPercent,
      @Schema(description = "UNSUPPORTED 항목이 있어 after가 일부 변경만 반영했으면 true") boolean partial,
      List<ContributionResponse> contributions,
      int unsupportedCount,
      List<String> assumptions) {
    static CombatPowerSimulationResponse from(CombatPowerSimulationService.Result result) {
      return new CombatPowerSimulationResponse(
          CombatPowerSimulationService.RULE_VERSION,
          result.characterName(),
          result.fetchedAt(),
          result.before(),
          result.modelBefore(),
          result.after(),
          result.delta(),
          result.deltaPercent(),
          result.partial(),
          result.contributions().stream()
              .map(
                  item ->
                      new ContributionResponse(
                          item.kind(),
                          item.target(),
                          item.from(),
                          item.to(),
                          item.delta(),
                          item.status().name(),
                          item.basis(),
                          item.reasonCode()))
              .toList(),
          result.unsupportedCount(),
          result.assumptions());
    }
  }

  public record ContributionResponse(
      @Schema(description = "요청 kind와 같은 문자열(응답에서는 enum으로 고정하지 않음)", example = "GEM_LEVEL")
          String kind,
      @Schema(description = "바꾼 대상 표시 이름") String target,
      @NullableResponse String from,
      @NullableResponse String to,
      @Schema(description = "순서대로 적용했을 때의 증가분. UNSUPPORTED면 null") @NullableResponse
          BigDecimal delta,
      @Schema(allowableValues = {"OK", "UNSUPPORTED"}) String status,
      @Schema(description = "근거 문장") String basis,
      @Schema(
              description = "UNSUPPORTED 이유 코드(문자열, 값이 늘 수 있음, 스펙업·전투력 기여와 같은 코드). OK면 null",
              example = "SUPPORTER_MODEL_PENDING")
          @NullableResponse
          String reasonCode) {}

  public record CombatPowerOptionsResponse(
      String ruleVersion,
      @Schema(description = "false면 숫자 범위가 공식 근거로 확정되기 전 예시") boolean verified,
      List<Integer> gemLevels,
      int refineMin,
      int refineMax,
      int advancedRefineMax,
      int qualityMax,
      List<Integer> engravingLevels,
      List<Integer> stoneLevels,
      List<String> arkPassiveTrees,
      List<String> coreGrades,
      int corePointMax,
      List<String> polishGrades,
      @Schema(description = "연마 옵션별 하·중·상 수치(공식 툴팁 표기 그대로, 고대 장신구). 표본에서 확인하지 못한 칸은 null")
          PolishValuesResponse polishValues,
      @Schema(
              description =
                  "팔찌 효과 교체에 쓰는 옵션 계열. name을 BRACELET_EFFECT의 name으로, grades는 LOW·MID·HIGH별 공식 문구. 줄을 빼려면 name \"없음\"")
          List<BraceletOptionResponse> braceletOptions,
      List<String> notes) {
    static CombatPowerOptionsResponse from(CombatPowerSimulationService.Options value) {
      return new CombatPowerOptionsResponse(
          value.ruleVersion(),
          value.verified(),
          value.gemLevels(),
          value.refineMin(),
          value.refineMax(),
          value.advancedRefineMax(),
          value.qualityMax(),
          value.engravingLevels(),
          value.stoneLevels(),
          value.arkPassiveTrees(),
          value.coreGrades(),
          value.corePointMax(),
          value.polishGrades(),
          new PolishValuesResponse(
              value.polishValues().version(),
              value.polishValues().accessoryGrade(),
              value.polishValues().itemLevel(),
              value.polishValues().options().stream()
                  .map(
                      item ->
                          new PolishValueResponse(
                              item.option(),
                              item.percent(),
                              item.parts(),
                              item.low(),
                              item.mid(),
                              item.high()))
                  .toList()),
          value.braceletOptions().stream()
              .map(option -> new BraceletOptionResponse(option.name(), option.grades()))
              .toList(),
          value.notes());
    }
  }

  public record BraceletOptionResponse(
      @Schema(description = "옵션 계열 이름(BRACELET_EFFECT의 name)", example = "추가 피해") String name,
      @Schema(description = "LOW·MID·HIGH → 공식 문구") java.util.Map<String, String> grades) {}

  public record PolishValuesResponse(
      @Schema(description = "수치 표 버전") String version,
      @Schema(description = "장신구 등급(지금은 고대만)") String accessoryGrade,
      @Schema(description = "수치가 맞는 장신구 아이템 레벨(지금은 1680만)", example = "1680") Integer itemLevel,
      List<PolishValueResponse> options) {}

  public record PolishValueResponse(
      @Schema(description = "연마 옵션 이름(공식 툴팁 그대로)") String option,
      @Schema(description = "true: % 옵션, false: 고정값 옵션(같은 이름의 공격력·무기 공격력을 가른다)") boolean percent,
      @Schema(description = "이 옵션이 붙는 부위(공식 연마 확률 정보: 목걸이·귀걸이·반지)") List<String> parts,
      @Schema(description = "하", example = "+0.70%") @NullableResponse String low,
      @Schema(description = "중", example = "+1.60%") @NullableResponse String mid,
      @Schema(description = "상", example = "+2.60%") @NullableResponse String high) {}

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "combat-power-sim", name = "enabled", havingValue = "true")
  static class CombatPowerOpenApiConfiguration {
    @Bean
    OpenApiCustomizer combatPowerResponseContract() {
      return document -> {
        ResponseContracts.apply(
            CombatPowerSimulationResponse.class, document.getComponents().getSchemas());
        ResponseContracts.apply(
            CombatPowerOptionsResponse.class, document.getComponents().getSchemas());
        ResponseContracts.apply(PolishValueResponse.class, document.getComponents().getSchemas());
      };
    }
  }
}
