package com.loakraken.character.presentation;

import com.loakraken.character.application.SnapshotHistoryQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CharacterSnapshotHistoryRequest(
    @Schema(
            description = "검색 응답의 캐릭터명",
            example = "모험가",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[\\p{L}\\p{N}]+")
        String characterName,
    @Schema(
            description = "검색 응답의 서버명",
            example = "루페온",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[\\p{L}\\p{N}]+")
        String serverName,
    @Schema(description = "한 페이지의 최대 개수", defaultValue = "20", minimum = "1", maximum = "50")
        @Min(1)
        @Max(50)
        Integer limit,
    @Schema(description = "이전 응답의 nextCursor. 첫 페이지는 생략합니다.", format = "uuid") UUID cursor) {
  public CharacterSnapshotHistoryRequest {
    characterName = characterName == null ? null : characterName.strip();
    serverName = serverName == null ? null : serverName.strip();
    limit = limit == null ? 20 : limit;
  }

  SnapshotHistoryQuery toQuery() {
    return new SnapshotHistoryQuery(characterName, serverName, limit, cursor);
  }
}
