package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterPublicSummaryService;
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
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "캐릭터", description = "캐릭터 검색 및 전투정보 조회")
public class CharacterPublicSummaryController {
  private final CharacterPublicSummaryService summaries;

  public CharacterPublicSummaryController(CharacterPublicSummaryService summaries) {
    this.summaries = summaries;
  }

  @GetMapping(
      value = "/api/v1/characters/public-summary",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "캐릭터 공개 요약(공식 호출 없음)",
      description =
          "캐릭터 페이지 첫 HTML(제목·설명·요약 줄·구조화 데이터)용 가벼운 요약입니다. 봇·사람 구분 없이 같은 값을 줍니다. "
              + "공식 API를 부르지 않고 캐릭터 캐시 → 저장된 마지막 관측 순서로 찾습니다. 둘 다 없으면 404 CHARACTER_NOT_CACHED입니다. "
              + "")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "CHARACTER_PUBLIC_SUMMARY_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_CACHED: 캐시에도 저장된 관측에도 없음(공식 호출 없음)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "CHARACTER_STORAGE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CharacterPublicSummaryResponse> publicSummary(
      @Valid @ModelAttribute @ParameterObject CharacterSiblingsRequest request) {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_PUBLIC_SUMMARY_SUCCESS,
        CharacterPublicSummaryResponse.from(summaries.summary(request.characterName())));
  }

  @Schema(description = "캐릭터 공개 요약(#145)")
  public record CharacterPublicSummaryResponse(
      String characterName,
      @NullableResponse String serverName,
      @NullableResponse String className,
      @NullableResponse Integer characterLevel,
      @NullableResponse BigDecimal itemLevel,
      @NullableResponse BigDecimal combatPower,
      @NullableResponse String arkPassiveTitle,
      @Schema(description = "이 값을 확인한 시각. STORED_OBSERVATION이면 화면에 '마지막 확인 시각'으로 보인다")
          @NullableResponse
          Instant observedAt,
      @Schema(description = "LIVE_CACHE: 최근 5분 안 공식 조회 캐시, STORED_OBSERVATION: 저장된 마지막 관측.")
          @NullableResponse
          CharacterPublicSummaryService.DataSource dataSource,
      @Schema(description = "false면 웹은 noindex·sitemap 제외") boolean indexable) {
    static CharacterPublicSummaryResponse from(CharacterPublicSummaryService.Summary summary) {
      return new CharacterPublicSummaryResponse(
          summary.characterName(),
          summary.serverName(),
          summary.className(),
          summary.characterLevel(),
          summary.itemLevel(),
          summary.combatPower(),
          summary.arkPassiveTitle(),
          summary.observedAt(),
          summary.dataSource(),
          summary.indexable());
    }
  }
}
