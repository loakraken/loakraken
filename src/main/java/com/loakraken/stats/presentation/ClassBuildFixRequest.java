package com.loakraken.stats.presentation;

import com.loakraken.stats.domain.ClassBuilds;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 직업 빌드 통계 고정 조건(M26 2단계). 모두 선택이고 합쳐 최대 3개다(각인·팔찌 특성은 값 하나가 조건 하나). */
public record ClassBuildFixRequest(
    @Schema(description = "이 각인을 모두 가진 캐릭터만(반복 가능)") @Size(max = ClassBuilds.MAX_FIXES)
        List<@Size(min = 1, max = 32) @Pattern(regexp = "[\\p{L}\\p{N} ]+") String> fixEngraving,
    @Schema(description = "가장 큰 카드 세트 이름이 이것인 캐릭터만")
        @Size(min = 1, max = 40)
        @Pattern(regexp = "[\\p{L}\\p{N} ]+")
        String fixCardSet,
    @Schema(description = "주력 전투 특성(치명·특화·신속·제압·인내·숙련)") @Pattern(regexp = "치명|특화|신속|제압|인내|숙련")
        String fixTopStat,
    @Schema(description = "진화 포인트") @Min(0) @Max(300) Integer fixEvolution,
    @Schema(description = "깨달음 포인트") @Min(0) @Max(300) Integer fixEnlightenment,
    @Schema(description = "도약 포인트") @Min(0) @Max(300) Integer fixLeap,
    @Schema(description = "팔찌에 이 전투 특성 줄이 모두 있는 캐릭터만(반복 가능, 팔찌 기준 레벨 이상)")
        @Size(max = ClassBuilds.MAX_FIXES)
        List<@Pattern(regexp = "치명|특화|신속|제압|인내|숙련") String> fixBracelet) {

  public ClassBuilds.Fix toFix() {
    return new ClassBuilds.Fix(
        fixEngraving == null ? List.of() : fixEngraving.stream().map(String::strip).toList(),
        fixCardSet == null ? null : fixCardSet.strip(),
        fixTopStat,
        fixEvolution,
        fixEnlightenment,
        fixLeap,
        fixBracelet);
  }

  @AssertTrue(message = "고정 조건은 합쳐서 3개까지입니다.")
  @Schema(hidden = true)
  public boolean isWithinFixLimit() {
    return toFix().count() <= ClassBuilds.MAX_FIXES;
  }
}
