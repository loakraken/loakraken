package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterSuggestPort;
import com.loakraken.character.application.CharacterSuggestService;
import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "캐릭터", description = "캐릭터 검색 및 전투정보 조회")
public class CharacterSuggestController {
  private final CharacterSuggestService suggestions;

  public CharacterSuggestController(CharacterSuggestService suggestions) {
    this.suggestions = suggestions;
  }

  @GetMapping(value = "/api/v1/characters/suggest", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "캐릭터 이름 자동완성(공식 호출 없음)",
      description =
          "LOAKraken에 기록된 캐릭터 중 이름이 q로 시작하는 캐릭터를 아이템 레벨 높은 순으로 최대 8개 줍니다. 공식 API를 부르지 않고, 검색한 사람과 입력은 기록하지 않습니다. "
              + "q는 앞뒤 공백을 지우고 NFC로 맞춥니다. 한글 완성형 글자만이면 1자부터, 그 밖에는 2자부터 찾고, 짧거나 12자를 넘으면 빈 목록입니다. "
              + "대소문자를 구분합니다. 응답은 60초 동안 브라우저 캐시할 수 있습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "CHARACTER_SUGGEST_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description = "요청이 너무 많음(클라이언트별 횟수 제한)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "CHARACTER_STORAGE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ResponseEntity<ApiResponse<CharacterSuggestResponse>> suggest(
      @Parameter(description = "이름 앞부분", example = "언새") @RequestParam(required = false) String q) {
    var items = suggestions.suggest(q).stream().map(CharacterSuggestItemResponse::from).toList();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
        .body(
            ApiResponse.of(
                CharacterSuccessCode.CHARACTER_SUGGEST_SUCCESS,
                new CharacterSuggestResponse(items)));
  }

  @Schema(description = "캐릭터 이름 자동완성(#369)")
  public record CharacterSuggestResponse(
      @Schema(description = "아이템 레벨 높은 순, 최대 8개. 없으면 빈 배열")
          List<CharacterSuggestItemResponse> items) {}

  public record CharacterSuggestItemResponse(
      String characterName,
      String serverName,
      String className,
      @Schema(description = "마지막으로 기록한 아이템 레벨") BigDecimal itemLevel,
      @Schema(description = "공식 프로필 초상 이미지 주소. 프로필을 아직 기록하지 않았으면 null") @NullableResponse
          String characterImage) {
    static CharacterSuggestItemResponse from(CharacterSuggestPort.Suggestion value) {
      return new CharacterSuggestItemResponse(
          value.characterName(),
          value.serverName(),
          value.className(),
          value.itemLevel(),
          value.imageUrl());
    }
  }
}
