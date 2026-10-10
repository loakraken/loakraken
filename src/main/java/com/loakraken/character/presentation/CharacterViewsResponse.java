package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterViewService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(
    description =
        "LOAKraken 캐릭터 검색 조회수(KST 일별). 조회한 사람을 식별하지 않아 같은 사람의 반복 조회도 셉니다. 개인정보는 저장하지 않습니다.")
public record CharacterViewsResponse(
    String characterName,
    String timezone,
    LocalDate from,
    LocalDate to,
    @Schema(description = "from~to의 모든 날짜(오래된 날부터, 조회 없으면 0)")
        List<CharacterDailyViewsResponse> days,
    @Schema(description = "기간 합계") long rangeTotal,
    @Schema(description = "집계 시작 이후 전체 합계") long total) {
  static CharacterViewsResponse from(CharacterViewService.Views views) {
    return new CharacterViewsResponse(
        views.characterName(),
        "Asia/Seoul",
        views.from(),
        views.to(),
        views.days().stream()
            .map(day -> new CharacterDailyViewsResponse(day.date(), day.count()))
            .toList(),
        views.rangeTotal(),
        views.total());
  }

  public record CharacterDailyViewsResponse(LocalDate date, long count) {}
}
