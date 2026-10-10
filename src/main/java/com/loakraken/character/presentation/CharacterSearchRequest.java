package com.loakraken.character.presentation;

import com.loakraken.character.application.SearchCharacterCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CharacterSearchRequest(
    @Schema(description = "검색할 캐릭터명", example = "모험가", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[\\p{L}\\p{N}]+")
        String characterName,
    @Schema(
            description =
                "원정대 포함 여부. 생략하거나 true면 원정대까지 조회합니다. false면 전투정보만 조회하며 siblings와 coverage.siblings는 null입니다. 원정대는 /api/v1/characters/siblings로 따로 조회합니다.",
            defaultValue = "true")
        Boolean includeSiblings) {
  public CharacterSearchRequest {
    characterName = characterName == null ? null : characterName.strip();
  }

  public SearchCharacterCommand toCommand() {
    return new SearchCharacterCommand(characterName, includeSiblings == null || includeSiblings);
  }
}
