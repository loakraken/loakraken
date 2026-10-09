package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterPopularService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record CharacterPopularRequest(
    @Pattern(regexp = "24h|7d")
        @Schema(
            description = "24h(최근 24시간) 또는 7d(최근 7일)",
            defaultValue = "24h",
            allowableValues = {"24h", "7d"})
        String period,
    @Min(1) @Max(CharacterPopularService.MAX_LIMIT) @Schema(description = "개수", defaultValue = "10")
        Integer limit) {
  public String periodOrDefault() {
    return period == null ? "24h" : period;
  }

  public int limitOrDefault() {
    return limit == null ? 10 : limit;
  }
}
