package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterQueryService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/characters", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "캐릭터", description = "캐릭터 검색 및 전투정보 조회")
public class CharacterController {
  private final CharacterQueryService queryService;

  public CharacterController(CharacterQueryService queryService) {
    this.queryService = queryService;
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
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_SEARCH_SUCCESS,
        CharacterResponse.from(queryService.search(request.toCommand())));
  }
}
