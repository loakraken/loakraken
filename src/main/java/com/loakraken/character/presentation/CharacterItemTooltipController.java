package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterItemTooltipService;
import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
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
              + "줄이 없는 항목은 목록에서 뺍니다. 처리정지 목록 캐릭터는 hidden=true이고 목록이 비어 있습니다.")
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

  @Schema(description = "아이템 툴팁 나머지 줄(#227). 처리정지 목록면 hidden=true이고 목록이 비어 있음")
  public record CharacterItemTooltipsResponse(
      String characterName,
      @Schema(description = "처리정지 목록 캐릭터면 true") boolean hidden,
      @Schema(
              description =
                  "이 줄을 읽은 캐릭터 캐시의 수집 시각. 캐릭터 검색 응답 fetchedAt과 다르면 순번이 어긋났을 수 있으니 버리고 다시 부른다. 숨김이면 null")
          @NullableResponse
          java.time.Instant fetchedAt,
      List<EquipmentLinesResponse> equipment,
      List<SlotLinesResponse> gems,
      List<SlotLinesResponse> cards,
      List<SkillLinesResponse> skills,
      @Schema(
              description =
                  "아크 그리드 코어·젬 툴팁 줄(#288). 키는 검색 응답 arkGrid.slots[].index와 그 gems[].index")
          List<GridLinesResponse> arkGrid) {
    static CharacterItemTooltipsResponse from(CharacterItemTooltipService.Tooltips value) {
      return new CharacterItemTooltipsResponse(
          value.characterName(),
          value.hidden(),
          value.fetchedAt(),
          value.equipment().stream()
              .map(
                  item ->
                      new EquipmentLinesResponse(
                          item.index(), item.type(), item.extraLines(), item.extraLineSections()))
              .toList(),
          value.gems().stream()
              .map(item -> new SlotLinesResponse(item.slot(), item.extraLines()))
              .toList(),
          value.cards().stream()
              .map(item -> new SlotLinesResponse(item.slot(), item.extraLines()))
              .toList(),
          value.skills().stream()
              .map(
                  item ->
                      new SkillLinesResponse(
                          item.name(),
                          item.runeExtraLines(),
                          item.titleLines(),
                          item.infoLines(),
                          item.tripodOptions().stream()
                              .map(
                                  option ->
                                      new TripodOptionResponse(
                                          option.tier(),
                                          option.slot(),
                                          option.name(),
                                          option.icon(),
                                          option.selected()))
                              .toList()))
              .toList(),
          value.arkGrid().stream()
              .map(
                  slot ->
                      new GridLinesResponse(
                          slot.index(),
                          slot.titleLine(),
                          slot.tradeLines(),
                          slot.extraLines(),
                          slot.gems().stream()
                              .map(
                                  gem ->
                                      new GridGemLinesResponse(
                                          gem.index(),
                                          gem.titleLine(),
                                          gem.tradeLines(),
                                          gem.extraLines()))
                              .toList()))
              .toList());
    }
  }

  public record GridLinesResponse(
      @Schema(description = "검색 응답 arkGrid.slots[].index", example = "0") @NullableResponse
          Integer index,
      @Schema(description = "공식 Tooltip 등급 줄", example = "유물 아크 그리드 코어") @NullableResponse
          String titleLine,
      @Schema(description = "공식 Tooltip 귀속·거래 줄(제목 바로 뒤)") List<String> tradeLines,
      @Schema(description = "공식 Tooltip 나머지 줄(원문 순서)", example = "[\"거래 불가\", \"분해불가\"]")
          List<String> extraLines,
      List<GridGemLinesResponse> gems) {}

  public record GridGemLinesResponse(
      @Schema(description = "검색 응답 arkGrid.slots[].gems[].index", example = "0") @NullableResponse
          Integer index,
      @Schema(description = "공식 Tooltip 등급 줄", example = "전설 아크 그리드 젬") @NullableResponse
          String titleLine,
      @Schema(description = "공식 Tooltip 귀속·거래 줄", example = "[\"가공 완료\", \"거래 불가\"]")
          List<String> tradeLines,
      @Schema(description = "공식 Tooltip 나머지 줄(원문 순서)") List<String> extraLines) {}

  public record EquipmentLinesResponse(
      @Schema(description = "검색 응답 loadout.equipment 배열 순번(0부터)", example = "0") int index,
      @Schema(description = "검색 응답 loadout.equipment[].type", example = "무기") String type,
      @Schema(
              description = "공식 Tooltip 나머지 줄(원문 순서)",
              example = "[\"분해불가, 품질 업그레이드 불가\", \"[제작] 대도시\", \"내구도 62 / 175\"]")
          List<String> extraLines,
      @Schema(
              description =
                  "extraLines와 같은 길이로, 각 줄 바로 앞에 나온 공식 Tooltip 섹션 제목(예: '팔찌 효과', '아크 패시브 포인트 효과'). 앞에 섹션이 없으면 빈 문자열. 캐시가 이전 형식이면 빈 목록이라 웹은 지금처럼 끝에 둔다(#285)",
              example = "[\"\", \"팔찌 효과\", \"팔찌 효과\"]")
          List<String> extraLineSections) {}

  public record SlotLinesResponse(
      @Schema(description = "검색 응답 gems.items[].slot 또는 cards.items[].slot", example = "0")
          @NullableResponse
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
          List<String> runeExtraLines,
      @Schema(
              description = "공식 Tooltip 제목 줄(아이콘 옆, 원문 순서: 스킬 종류·재사용 대기시간·분류). 이전 캐시면 빈 목록",
              example = "[\"일반\", \"재사용 대기시간 5초\", \"[일반 스킬]\"]")
          List<String> titleLines,
      @Schema(
              description =
                  "공식 Tooltip 현재 레벨 정보 줄(원문 순서: PvE·스킬 레벨·마나 소모·부위 파괴·무력화·공격 타입·슈퍼아머 등). 다음 레벨 정보·트라이포드·룬·보석 효과는 빼고, 이전 캐시면 빈 목록",
              example =
                  "[\"PvE\", \"스킬 레벨 10\", \"마나 349 소모\", \"공격 타입 : 백 어택\", \"슈퍼아머 : 경직 면역\"]")
          List<String> infoLines,
      @Schema(
              description =
                  "공식 트라이포드 전체 선택지(선택 여부 포함, 공식 순서). 효과 문장은 검색 응답 skills[].tripods(선택된 것)에만 있음. 이전 캐시면 빈 목록")
          List<TripodOptionResponse> tripodOptions) {}

  public record TripodOptionResponse(
      @Schema(description = "공식 Tier(0부터)", example = "0") @NullableResponse Integer tier,
      @Schema(description = "공식 Slot(1부터)", example = "1") @NullableResponse Integer slot,
      @Schema(example = "약점 노출") String name,
      @NullableResponse String icon,
      @Schema(description = "선택한 트라이포드면 true") boolean selected) {}
}
