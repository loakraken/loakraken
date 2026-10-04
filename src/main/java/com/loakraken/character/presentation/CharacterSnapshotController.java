package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterSnapshotQueryService;
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
@Tag(name = "캐릭터")
public class CharacterSnapshotController {
  private final CharacterSnapshotQueryService queryService;

  public CharacterSnapshotController(CharacterSnapshotQueryService queryService) {
    this.queryService = queryService;
  }

  @GetMapping("/snapshots")
  @Operation(
      summary = "저장된 캐릭터 관측 이력",
      description =
          "관측 이력 기능은 기본 비활성화 상태로 503을 반환합니다. 활성화된 환경에서는 서버와 캐릭터명이 일치하는 저장 기록만 최신순으로 읽습니다. 외부 API를 호출하지 않습니다. 동일 시각은 저장 순서 역순이며 동일 원본 재조회는 새 기록을 만들지 않습니다. 활성 상태에서 기록이 없으면 빈 목록입니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "저장된 관측 이력 조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST 또는 INVALID_SNAPSHOT_CURSOR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "406",
        description = "NOT_ACCEPTABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        description = "INTERNAL_SERVER_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description =
            "CHARACTER_HISTORY_DISABLED: 기능 비활성화 / CHARACTER_HISTORY_UNAVAILABLE: 저장소 장애 또는 처리할 수 없는 기록",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CharacterSnapshotHistoryResponse> history(
      @Valid @ModelAttribute @ParameterObject CharacterSnapshotHistoryRequest request) {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_HISTORY_SUCCESS,
        CharacterSnapshotHistoryResponse.from(queryService.history(request.toQuery())));
  }
}
