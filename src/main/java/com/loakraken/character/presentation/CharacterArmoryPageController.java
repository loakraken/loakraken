package com.loakraken.character.presentation;

import com.loakraken.character.application.ArmoryPageService;
import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "캐릭터", description = "캐릭터 검색 및 전투정보 조회")
public class CharacterArmoryPageController {
  private final ArmoryPageService pages;

  public CharacterArmoryPageController(ArmoryPageService pages) {
    this.pages = pages;
  }

  @GetMapping(value = "/api/v1/characters/armory-page", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "전투정보실 페이지 정보(생활 스킬 레벨)",
      description =
          "공식 Open API에 없는 생활 스킬 레벨을 공식 전투정보실 캐릭터 페이지에서 읽어 줍니다. "
              + "사용자가 검색한 캐릭터(캐릭터 캐시에 있음)만 읽고, 캐시가 없으면 404 CHARACTER_NOT_CACHED입니다. "
              + "기능이 꺼져 있으면 200에 enabled=false이고 나머지 값은 null입니다. "
              + "켜져 있어도 읽지 못하면(요청 간격·쉬는 중·멈춤·요청 실패) enabled=true에 나머지 값이 null이며, 웹은 그 자리를 숨깁니다. "
              + "읽은 값은 6시간 동안 다시 읽지 않습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "CHARACTER_ARMORY_PAGE_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_CACHED: 검색한 적 없는 캐릭터(공식 요청 없음)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "CHARACTER_CACHE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CharacterArmoryPageResponse> armoryPage(
      @Valid @ModelAttribute @ParameterObject CharacterSiblingsRequest request) {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_ARMORY_PAGE_SUCCESS,
        CharacterArmoryPageResponse.from(pages.page(request.characterName())));
  }

  @Schema(description = "전투정보실 페이지 정보")
  public record CharacterArmoryPageResponse(
      String characterName,
      @Schema(description = "기능이 켜져 있으면 true. false면 나머지 값은 모두 null") boolean enabled,
      @Schema(description = "값을 읽은 곳(읽지 못했으면 null)", example = "공식 전투정보실") @NullableResponse
          String source,
      @Schema(description = "공식 페이지를 읽은 시각(읽지 못했으면 null)") @NullableResponse Instant fetchedAt,
      @Schema(description = "생활 스킬(공식 순서). 읽지 못했으면 null") @NullableResponse
          List<LifeSkillResponse> lifeSkills) {
    static CharacterArmoryPageResponse from(ArmoryPageService.View view) {
      return new CharacterArmoryPageResponse(
          view.characterName(),
          view.enabled(),
          view.source(),
          view.fetchedAt(),
          view.lifeSkills() == null
              ? null
              : view.lifeSkills().stream()
                  .map(skill -> new LifeSkillResponse(skill.name(), skill.level()))
                  .toList());
    }
  }

  public record LifeSkillResponse(
      @Schema(example = "고고학") String name,
      @Schema(description = "생활 스킬 레벨(읽지 못했으면 null)", example = "70") @NullableResponse
          Integer level) {}
}
