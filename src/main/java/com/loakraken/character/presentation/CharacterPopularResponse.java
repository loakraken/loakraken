package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterPopularPort.PopularResult;
import com.loakraken.character.application.CharacterPopularService;
import com.loakraken.common.api.NullableResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(
    description =
        "인기 검색 캐릭터(popular-v1). 순위 기준은 KST 하루 1회 중복을 제거한 고유 조회 수의 기간 합계이며, 숫자는 공개하지 않습니다.")
public record CharacterPopularResponse(
    @Schema(allowableValues = {"24h", "7d"}) String period,
    Instant aggregatedAt,
    @Schema(
            description = "COLLECTING이면 items가 비어 있습니다",
            allowableValues = {"COLLECTING", "READY"})
        String sampleStatus,
    @Schema(description = "순위에 오르는 최소 고유 조회 수") int minUniqueViews,
    @Schema(example = "popular-v1") String ruleVersion,
    List<CharacterPopularItemResponse> items) {
  static CharacterPopularResponse from(PopularResult result) {
    return new CharacterPopularResponse(
        result.period(),
        result.aggregatedAt(),
        result.sampleStatus(),
        result.minUniqueViews(),
        CharacterPopularService.RULE_VERSION,
        result.items().stream()
            .map(
                item ->
                    new CharacterPopularItemResponse(
                        item.rank(),
                        item.characterName(),
                        item.serverName(),
                        item.className(),
                        item.itemLevel()))
            .toList());
  }

  public record CharacterPopularItemResponse(
      int rank,
      String characterName,
      String serverName,
      @NullableResponse @Schema(description = "최근 관측값. 없으면 null") String className,
      @NullableResponse @Schema(description = "최근 관측값. 없으면 null") BigDecimal itemLevel) {}
}
