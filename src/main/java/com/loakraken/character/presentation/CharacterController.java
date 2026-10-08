package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterQueryService;
import com.loakraken.character.application.CharacterViewService;
import com.loakraken.character.application.CritSpeedService;
import com.loakraken.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/characters", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "캐릭터", description = "캐릭터 검색 및 전투정보 조회")
public class CharacterController {
  private final CharacterQueryService queryService;
  private final CritSpeedService critSpeed;
  private final CharacterViewService views;

  public CharacterController(
      CharacterQueryService queryService, CritSpeedService critSpeed, CharacterViewService views) {
    this.queryService = queryService;
    this.critSpeed = critSpeed;
    this.views = views;
  }

  @GetMapping("/search")
  @Operation(
      summary = "캐릭터 검색",
      description =
          "프로필, 장비·보석·각인·카드·아크패시브·아크그리드와 원정대를 제공합니다. 기본 캐시는 5분이며 fetchedAt은 실제 외부 수집 완료 시각입니다. null 섹션은 공식 API에서 데이터가 제공되지 않은 경우입니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "캐릭터 조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "캐릭터명 누락 또는 형식 오류",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_FOUND",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "406",
        description = "NOT_ACCEPTABLE: JSON 이외의 응답 형식 요청",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description = "LOST_ARK_RATE_LIMITED",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        description = "INTERNAL_SERVER_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "502",
        description = "LOST_ARK_API_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description =
            "LOST_ARK_NOT_CONFIGURED, CHARACTER_CACHE_UNAVAILABLE 또는 CHARACTER_STORAGE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "504",
        description = "LOST_ARK_API_TIMEOUT",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CharacterResponse> search(
      @Valid @ModelAttribute @ParameterObject CharacterSearchRequest request) {
    var result = queryService.search(request.toCommand());
    // 조회수는 검색 화면 응답이 성공했을 때만 센다(F-8). 기록 실패는 응답을 막지 않는다.
    views.record(result.details().profile().characterName());
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_SEARCH_SUCCESS, CharacterResponse.from(result));
  }

  @GetMapping("/views")
  @Operation(
      summary = "캐릭터 조회수",
      description =
          "LOAKraken 캐릭터 검색 성공 횟수를 KST 일별로 집계한 값입니다. 최근 days일(기본 112일=16주)을 0으로 채워 주고 기간·전체 합계를 함께 줍니다. "
              + "외부 호출이 없습니다. 조회한 사람을 식별하지 않으므로 반복 조회도 셉니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "캐릭터명·일수 오류",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "CHARACTER_VIEWS_DISABLED 또는 CHARACTER_VIEWS_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CharacterViewsResponse> views(
      @Valid @ModelAttribute @ParameterObject CharacterViewsRequest request) {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_VIEWS_SUCCESS,
        CharacterViewsResponse.from(views.views(request.characterName(), request.daysOrDefault())));
  }

  @GetMapping("/siblings")
  @Operation(
      summary = "원정대 캐릭터 조회",
      description =
          "공식 원정대 목록만 조회합니다. 캐릭터 검색과 별도로 캐시(기본 5분)하며 같은 이름의 동시 요청을 합칩니다. 공식 API가 빈 목록을 반환하면 존재하지 않는 캐릭터로 처리합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "원정대 조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "캐릭터명 누락 또는 형식 오류",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_FOUND: 공식 API 404 또는 빈 원정대 목록",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "406",
        description = "NOT_ACCEPTABLE: JSON 이외의 응답 형식 요청",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description = "LOST_ARK_RATE_LIMITED",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        description = "INTERNAL_SERVER_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "502",
        description = "LOST_ARK_API_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "LOST_ARK_NOT_CONFIGURED 또는 CHARACTER_CACHE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "504",
        description = "LOST_ARK_API_TIMEOUT",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CharacterSiblingsResponse> siblings(
      @Valid @ModelAttribute @ParameterObject CharacterSiblingsRequest request) {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_SIBLINGS_SUCCESS,
        CharacterSiblingsResponse.from(queryService.siblings(request.toCommand())));
  }

  @PostMapping(value = "/crit-speed", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "치명타·공이속 계산(치적·뭉가·음돌)",
      description =
          "캐릭터 검색 캐시를 공유해 공식 원문(치명·신속 Tooltip, 각인, 아크 패시브 노드, 장신구 연마, 팔찌, 카드)의 치명타 적중률과 공격·이동 속도를 합산하고, 요청 가정(백어택·펫·시너지·버프 등)을 분리해 더합니다. "
              + "치적 100%·뭉툭한 가시 최적 치적까지 필요한 치명과 음속 돌파 최적 속도까지 필요한 신속을 계산합니다. 원정대는 조회하지 않습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "계산 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST: 캐릭터명·가정 범위 오류",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_FOUND",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "415",
        description = "UNSUPPORTED_MEDIA_TYPE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description = "LOST_ARK_RATE_LIMITED",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "502",
        description = "LOST_ARK_API_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "LOST_ARK_NOT_CONFIGURED 또는 CHARACTER_CACHE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "504",
        description = "LOST_ARK_API_TIMEOUT",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CritSpeedResponse> critSpeed(@Valid @RequestBody CritSpeedRequest request) {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_CRIT_SPEED_SUCCESS,
        CritSpeedResponse.from(
            critSpeed.calculate(request.normalizedName(), request.toAssumptions())));
  }
}
