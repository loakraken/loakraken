package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterItemTooltipService;
import com.loakraken.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "캐릭터", description = "캐릭터 검색 및 전투정보 조회")
public class CharacterItemTooltipController {
  private final CharacterItemTooltipService tooltips;

  public CharacterItemTooltipController(CharacterItemTooltipService tooltips) {
    this.tooltips = tooltips;
  }

  @GetMapping(
      value = "/api/v1/characters/item-tooltips",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "아이템 툴팁 나머지 줄(공식 호출 없음)",
      description =
          "캐릭터 검색 응답에 넣지 않은 공식 Tooltip 나머지 줄(extraLines: 내구도·분해불가·설명·획득처 등)을 줍니다(#227). "
              + "공식 API를 부르지 않고 캐릭터 캐시만 읽습니다. 캐시가 없으면 404 CHARACTER_NOT_CACHED이며, 웹은 검색 응답의 짧은 필드만으로 툴팁을 그립니다. "
              + "항목은 검색 응답과 키로 잇습니다: equipment는 loadout.equipment 순번(index, 0부터)과 type, gems·cards는 slot, skills는 name. "
              + "줄이 없는 항목은 목록에서 뺍니다. 공개 제외 캐릭터는 hidden=true이고 목록이 비어 있습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "CHARACTER_ITEM_TOOLTIPS_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_CACHED: 캐릭터 캐시가 없음(공식 호출 없음)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "CHARACTER_STORAGE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CharacterItemTooltipsResponse> itemTooltips(
      @Valid @ModelAttribute @ParameterObject CharacterSiblingsRequest request) {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_ITEM_TOOLTIPS_SUCCESS,
        CharacterItemTooltipsResponse.from(tooltips.tooltips(request.characterName())));
  }

  @Schema(description = "아이템 툴팁 나머지 줄(#227). 공개 제외면 hidden=true이고 목록이 비어 있음")
  public record CharacterItemTooltipsResponse(
      String characterName,
      @Schema(description = "공개 제외 캐릭터면 true") boolean hidden,
      @Schema(
              description =
                  "이 줄을 읽은 캐릭터 캐시의 수집 시각. 캐릭터 검색 응답 fetchedAt과 다르면 순번이 어긋났을 수 있으니 버리고 다시 부른다. 숨김이면 null")
          @com.loakraken.common.api.NullableResponse
          java.time.Instant fetchedAt,
      List<EquipmentLinesResponse> equipment,
      List<SlotLinesResponse> gems,
      List<SlotLinesResponse> cards,
      List<SkillLinesResponse> skills) {
    static CharacterItemTooltipsResponse from(CharacterItemTooltipService.Tooltips value) {
      return new CharacterItemTooltipsResponse(
          value.characterName(),
          value.hidden(),
          value.fetchedAt(),
          value.equipment().stream()
              .map(item -> new EquipmentLinesResponse(item.index(), item.type(), item.extraLines()))
              .toList(),
          value.gems().stream()
              .map(item -> new SlotLinesResponse(item.slot(), item.extraLines()))
              .toList(),
          value.cards().stream()
              .map(item -> new SlotLinesResponse(item.slot(), item.extraLines()))
              .toList(),
          value.skills().stream()
              .map(item -> new SkillLinesResponse(item.name(), item.runeExtraLines()))
              .toList());
    }
  }

  public record EquipmentLinesResponse(
      @Schema(description = "검색 응답 loadout.equipment 배열 순번(0부터)", example = "0") int index,
      @Schema(description = "검색 응답 loadout.equipment[].type", example = "무기") String type,
      @Schema(
              description = "공식 Tooltip 나머지 줄(원문 순서)",
              example = "[\"분해불가, 품질 업그레이드 불가\", \"[제작] 대도시\", \"내구도 62 / 175\"]")
          List<String> extraLines) {}

  public record SlotLinesResponse(
      @Schema(description = "검색 응답 gems.items[].slot 또는 cards.items[].slot", example = "0")
          @com.loakraken.common.api.NullableResponse
          Integer slot,
      @Schema(
              description = "공식 Tooltip 나머지 줄(원문 순서)",
              example = "[\"보석 레벨 6\", \"분해불가, 보석 합성 불가\"]")
          List<String> extraLines) {}

  public record SkillLinesResponse(
      @Schema(description = "검색 응답 skills[].name", example = "블레이드 버스트") String name,
      @Schema(
              description = "룬 Tooltip 나머지 줄(설명·획득처)",
              example = "[\"스킬에 강력한 힘을 부여할 수 있는 특별한 룬이다.\"]")
          List<String> runeExtraLines) {}
}
