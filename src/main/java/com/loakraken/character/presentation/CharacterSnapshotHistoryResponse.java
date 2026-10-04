package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterSnapshotPage;
import com.loakraken.common.api.NullableResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "저장된 관측 기록. 실시간 조회나 전체 성장 이력이 아니며 기록이 없으면 items는 빈 배열입니다.")
public record CharacterSnapshotHistoryResponse(
    List<SnapshotSummaryResponse> items,
    @NullableResponse @Schema(description = "다음 페이지의 cursor. 마지막 페이지이면 null입니다.") UUID nextCursor) {
  static CharacterSnapshotHistoryResponse from(CharacterSnapshotPage page) {
    return new CharacterSnapshotHistoryResponse(
        page.items().stream()
            .map(
                item ->
                    new SnapshotSummaryResponse(
                        item.snapshotId(),
                        item.fetchedAt(),
                        item.className(),
                        item.itemLevel(),
                        item.combatPower()))
            .toList(),
        page.nextCursor());
  }

  public record SnapshotSummaryResponse(
      UUID snapshotId,
      @Schema(description = "이 기록을 외부에서 수집한 시각. 실제 강화 또는 세팅 변경 시각이 아닙니다.") Instant fetchedAt,
      String className,
      BigDecimal itemLevel,
      @NullableResponse BigDecimal combatPower) {}
}
