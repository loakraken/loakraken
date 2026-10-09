package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterQueryService;
import com.loakraken.character.application.CharacterTitleService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "캐릭터", description = "캐릭터 검색 및 전투정보 조회")
public class CharacterTitleController {
  private final CharacterQueryService queries;
  private final CharacterTitleService titles;

  public CharacterTitleController(CharacterQueryService queries, CharacterTitleService titles) {
    this.queries = queries;
    this.titles = titles;
  }

  @GetMapping(value = "/api/v1/characters/titles", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "원정대 보유 칭호(관측 기준)",
      description =
          "같은 서버 원정대 캐릭터가 LOAKraken에서 검색·수집될 때 장착한 칭호를 처음 확인한 순서로 줍니다. 장착한 칭호만 기록되며 실제 획득일·획득 순서와 다를 수 있습니다. "
              + "공식 API는 현재 장착 칭호 하나만 제공합니다. 원정대 목록은 /siblings 캐시를 공유합니다(공식 호출 최대 1회).")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공(기록 없음 포함)"),
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
        description = "LOST_ARK_RATE_LIMITED",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "502",
        description = "LOST_ARK_API_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description =
            "CHARACTER_HISTORY_DISABLED, CHARACTER_HISTORY_UNAVAILABLE, LOST_ARK_NOT_CONFIGURED 또는 CHARACTER_CACHE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "504",
        description = "LOST_ARK_API_TIMEOUT",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<RosterTitlesResponse> titles(
      @Valid @ModelAttribute @ParameterObject CharacterSiblingsRequest request) {
    var roster = queries.siblings(request.toCommand());
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_TITLES_SUCCESS,
        RosterTitlesResponse.from(titles.roster(request.characterName(), roster.siblings())));
  }
}
