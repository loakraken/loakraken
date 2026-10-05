package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterHistoryQuery;
import com.loakraken.character.application.CharacterObservationPoint;
import com.loakraken.common.api.NullableResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "그래프용 캐릭터 관측 이력. 최근 관측부터 limit개를 시간 오름차순으로 제공합니다.")
public record CharacterHistoryResponse(
    String characterName,
    String serverName,
    @Schema(description = "값이 바뀐 관측만 한 점이 됩니다. 같은 값의 재수집은 lastConfirmedAt만 갱신합니다.")
        List<PointResponse> points) {
  static CharacterHistoryResponse from(
      CharacterHistoryQuery query, List<CharacterObservationPoint> points) {
    return new CharacterHistoryResponse(
        query.characterName(),
        query.serverName(),
        points.stream()
            .map(
                point ->
                    new PointResponse(
                        point.observedAt(),
                        point.lastConfirmedAt(),
                        point.className(),
                        point.characterLevel(),
                        point.itemLevel(),
                        point.combatPower(),
                        point.expeditionLevel()))
            .toList());
  }

  public record PointResponse(
      @Schema(description = "이 값이 처음 관측된 UTC 시각") Instant observedAt,
      @Schema(description = "같은 값이 마지막으로 확인된 UTC 시각") Instant lastConfirmedAt,
      String className,
      @NullableResponse Integer characterLevel,
      BigDecimal itemLevel,
      @NullableResponse BigDecimal combatPower,
      @NullableResponse Integer expeditionLevel) {}
}
