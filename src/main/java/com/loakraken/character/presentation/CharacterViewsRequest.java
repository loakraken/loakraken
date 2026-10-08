package com.loakraken.character.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CharacterViewsRequest(
    @Schema(description = "캐릭터명", example = "모험가", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[\\p{L}\\p{N}]+")
        String characterName,
    @Schema(description = "오늘(KST)까지 일수. 기본 112(16주)", defaultValue = "112") @Min(1) @Max(366)
        Integer days) {
  public CharacterViewsRequest {
    characterName = characterName == null ? null : characterName.strip();
  }

  int daysOrDefault() {
    return days == null ? 112 : days;
  }
}
