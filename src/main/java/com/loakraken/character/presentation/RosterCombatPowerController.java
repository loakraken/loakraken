package com.loakraken.character.presentation;

import com.loakraken.character.application.RosterCombatPowerService;
import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/characters", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "캐릭터", description = "캐릭터 검색 및 전투정보 조회")
public class RosterCombatPowerController {
  private final RosterCombatPowerService service;

  public RosterCombatPowerController(RosterCombatPowerService service) {
    this.service = service;
  }

  @GetMapping("/roster-combat-power")
  @Operation(
      summary = "원정대 캐릭터별 전투력",
      description =
          "같은 서버 원정대 캐릭터마다 LOAKraken에 기록된 전투력과 그 값을 확인한 시각을 줍니다. 원정대 목록은 원정대 조회와 같은 캐시를 쓰고(없으면 공식 1회), 전투력은 기록만 읽습니다. "
              + "기록이 없으면 combatPower가 null이고, 불러오기(POST …/refresh)로 채울 수 있습니다. 정렬·식스맨·총합은 화면이 계산합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "CHARACTER_ROSTER_COMBAT_POWER_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_FOUND",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "CHARACTER_STORAGE_UNAVAILABLE 또는 LOST_ARK_NOT_CONFIGURED",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<RosterCombatPowerResponse> view(
      @Valid @ModelAttribute @ParameterObject CharacterSiblingsRequest request) {
    var view = service.view(request.characterName());
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_ROSTER_COMBAT_POWER_SUCCESS,
        new RosterCombatPowerResponse(
            items(view.items()), view.missingCount(), view.refreshAvailableAt()));
  }

  @PostMapping("/roster-combat-power/refresh")
  @Operation(
      summary = "원정대 전투력 불러오기",
      description =
          "전투력 기록이 없거나 24시간 넘은 원정대 캐릭터를 아이템 레벨 높은 순으로 최대 12명 공식 프로필에서 불러와 기록합니다. 같은 원정대는 10분에 한 번이고(429 ROSTER_REFRESH_COOLDOWN, Retry-After), "
              + "같은 원정대 동시 요청은 한 번으로 묶습니다. 공식 호출 여유가 모자라면 받은 만큼만 기록하고 200에 remaining을 줍니다. 클라이언트당 분당 2회입니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "CHARACTER_ROSTER_COMBAT_POWER_REFRESH_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_FOUND",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description = "ROSTER_REFRESH_COOLDOWN(Retry-After 헤더) 또는 클라이언트 요청이 너무 많음",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description =
            "CHARACTER_STORAGE_UNAVAILABLE, CHARACTER_CACHE_UNAVAILABLE 또는 LOST_ARK_NOT_CONFIGURED",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<RosterCombatPowerRefreshResponse> refresh(
      @Valid @ModelAttribute @ParameterObject CharacterSiblingsRequest request) {
    var result = service.refresh(request.characterName());
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_ROSTER_COMBAT_POWER_REFRESH_SUCCESS,
        new RosterCombatPowerRefreshResponse(
            items(result.items()),
            result.fetched(),
            result.remaining(),
            result.refreshAvailableAt()));
  }

  private static List<RosterCombatPowerItemResponse> items(
      List<RosterCombatPowerService.Item> items) {
    return items.stream()
        .map(
            item ->
                new RosterCombatPowerItemResponse(
                    item.characterName(),
                    item.serverName(),
                    item.className(),
                    item.itemLevel(),
                    item.combatPower(),
                    item.combatPowerObservedAt(),
                    item.bestCombatPower()))
        .toList();
  }

  @Schema(description = "원정대 캐릭터별 전투력")
  public record RosterCombatPowerResponse(
      @Schema(description = "같은 서버 원정대 캐릭터(공식 원정대 순서)") List<RosterCombatPowerItemResponse> items,
      @Schema(description = "전투력 기록이 없는 캐릭터 수") int missingCount,
      @Schema(description = "다시 불러올 수 있는 시각. 쿨다운 중이 아니면 null") @NullableResponse
          Instant refreshAvailableAt) {}

  @Schema(description = "원정대 전투력 불러오기 결과")
  public record RosterCombatPowerRefreshResponse(
      @Schema(description = "불러온 뒤의 원정대 캐릭터별 전투력") List<RosterCombatPowerItemResponse> items,
      @Schema(description = "이번에 공식 프로필을 부른 캐릭터 수") int fetched,
      @Schema(description = "아직 부르지 않은 대상 수(12명 상한·공식 호출 여유 부족). 0보다 크면 다시 불러오기") int remaining,
      @Schema(description = "다시 불러올 수 있는 시각. 한 명도 못 불렀으면 null(바로 다시 가능)") @NullableResponse
          Instant refreshAvailableAt) {}

  public record RosterCombatPowerItemResponse(
      String characterName,
      String serverName,
      @Schema(description = "공식 원정대 목록의 직업") @NullableResponse String className,
      @Schema(description = "공식 원정대 목록의 아이템 레벨") @NullableResponse BigDecimal itemLevel,
      @Schema(description = "LOAKraken에 기록된 전투력. 기록이 없으면 null") @NullableResponse
          BigDecimal combatPower,
      @Schema(description = "그 전투력을 확인한 시각. 기록이 없으면 null") @NullableResponse
          Instant combatPowerObservedAt,
      @Schema(description = "LOAKraken이 관측한 최고 전투력(전투력 랭킹의 bestCombatPower와 같은 값). 기록이 없으면 null")
          @NullableResponse
          BigDecimal bestCombatPower) {}
}
