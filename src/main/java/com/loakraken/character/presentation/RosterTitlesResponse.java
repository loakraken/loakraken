package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterTitleService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(
    description =
        "원정대 보유 칭호(title-record-v1). LOAKraken에서 처음 확인한 순서입니다. 장착한 칭호만 기록되며 실제 획득일·획득 순서와 다를 수 있습니다. 기록이 없어도 200입니다.")
public record RosterTitlesResponse(
    String characterName,
    String serverName,
    @Schema(description = "같은 서버 원정대 캐릭터 수") int rosterCharacters,
    @Schema(description = "그중 칭호 기록이 있는 캐릭터 수") int observedCharacters,
    @Schema(example = "title-record-v1") String ruleVersion,
    @Schema(description = "처음 확인한 순서(observedOrder 오름차순)") List<RosterTitleResponse> titles) {
  static RosterTitlesResponse from(CharacterTitleService.RosterTitles result) {
    return new RosterTitlesResponse(
        result.characterName(),
        result.serverName(),
        result.rosterCharacters(),
        result.observedCharacters(),
        CharacterTitleService.RULE_VERSION,
        result.titles().stream()
            .map(
                title ->
                    new RosterTitleResponse(
                        title.title(),
                        title.firstObservedAt(),
                        java.time.LocalDate.ofInstant(title.firstObservedAt(), KST),
                        title.lastObservedAt(),
                        title.observedOrder(),
                        title.recordedBy(),
                        "OBSERVED"))
            .toList());
  }

  static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");

  public record RosterTitleResponse(
      String title,
      @Schema(description = "LOAKraken이 이 칭호를 장착 상태로 처음 확인한 시각(획득일 아님)") Instant firstObservedAt,
      @Schema(
              description =
                  "firstObservedAt의 KST 날짜. 같은 날 처음 확인한 칭호는 한 번에 기록된 것이라 observedOrder의 앞뒤에 의미가 없다(#144)")
          java.time.LocalDate firstObservedDate,
      @Schema(description = "마지막으로 장착 상태를 확인한 시각") Instant lastObservedAt,
      @Schema(description = "처음 확인한 순서(1부터). 획득 순서가 아님") int observedOrder,
      @Schema(description = "처음 확인한 원정대 캐릭터") String recordedBy,
      @Schema(
              description = "OBSERVED: LOAKraken 관측. 공식 보유 목록이 생기면 OFFICIAL을 추가한다",
              allowableValues = {"OBSERVED", "OFFICIAL"})
          String source) {}
}
