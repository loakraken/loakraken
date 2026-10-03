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
      description = "현재는 프로필을 제공합니다. 5분 캐시를 기본으로 사용하며 fetchedAt은 실제 외부 수집 시각입니다.")
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
  public ApiResponse<CharacterResponse> search(
      @Valid @ModelAttribute @ParameterObject CharacterSearchRequest request) {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_SEARCH_SUCCESS,
        CharacterResponse.from(queryService.search(request.toCommand())));
  }
}
