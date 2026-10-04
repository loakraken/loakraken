package com.loakraken.character.presentation;

import com.loakraken.character.domain.CharacterDiagnostics;
import com.loakraken.common.api.NullableResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "관측값 기반 진단. 빈 findings는 스펙 완성이나 레이드 적합성을 보증하지 않습니다.")
public record CharacterDiagnosticsResponse(
    @Schema(description = "진단 규칙 버전. 수집 시각은 최상위 fetchedAt을 사용합니다.", example = "observed-v2")
        String ruleVersion,
    @Schema(description = "장비·보석·카드·각인·패시브·그리드 순서. 성능 또는 강화 우선순위가 아닙니다.")
        List<DiagnosticFindingResponse> findings) {
  static CharacterDiagnosticsResponse from(CharacterDiagnostics diagnostics) {
    return new CharacterDiagnosticsResponse(
        diagnostics.ruleVersion(),
        diagnostics.findings().stream()
            .map(
                finding ->
                    new DiagnosticFindingResponse(
                        finding.code(),
                        finding.category(),
                        finding.evidencePath(),
                        finding.observedCount(),
                        finding.message()))
            .toList());
  }

  public record DiagnosticFindingResponse(
      CharacterDiagnostics.Code code,
      @Schema(description = "DATA_GAP: 정보 부족, SETTING_CHECK: 관측된 비활성 세팅 확인. 심각도 등급이 아닙니다.")
          CharacterDiagnostics.Category category,
      @Schema(
              description = "전체 API 응답 기준 JSON Pointer. 해당 필드의 관측값이 진단의 근거입니다.",
              example = "/data/analysis/arkGrid/inactiveGemCount")
          String evidencePath,
      @NullableResponse @Schema(description = "개수 기반 진단의 관측 개수. 불리언 또는 미제공 값의 진단이면 null입니다.")
          Integer observedCount,
      @Schema(description = "표시용 설명. 클라이언트 분기는 message 대신 code를 사용합니다.") String message) {}
}
