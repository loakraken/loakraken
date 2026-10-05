package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterHistoryQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CharacterHistoryRequest(
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
    @Schema(description = "최근 관측 최대 개수", defaultValue = "100", minimum = "1", maximum = "500")
        @Min(1)
        @Max(500)
        Integer limit) {
  public CharacterHistoryRequest {
    characterName = characterName == null ? null : characterName.strip();
    serverName = serverName == null ? null : serverName.strip();
    limit = limit == null ? 100 : limit;
  }

  CharacterHistoryQuery toQuery() {
    return new CharacterHistoryQuery(characterName, serverName, limit);
  }
}
