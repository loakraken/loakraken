package com.loakraken.character.presentation;

import com.loakraken.character.application.SearchCharacterCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CharacterSiblingsRequest(
    @Schema(
            description = "원정대를 조회할 캐릭터명",
            example = "모험가",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[\\p{L}\\p{N}]+")
        String characterName) {
  public CharacterSiblingsRequest {
    characterName = characterName == null ? null : characterName.strip();
  }

  public SearchCharacterCommand toCommand() {
    return new SearchCharacterCommand(characterName);
  }
}
