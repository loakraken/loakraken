package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterCardRefreshService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "원정대 카드 기록 채우기 결과")
public record CardCollectionRefreshResponse(
    @Schema(description = "같은 서버 원정대 캐릭터 수") int rosterCharacters,
    @Schema(description = "이번에 조회해 기록한 캐릭터 수") int refreshed,
    @Schema(description = "최근 7일 안에 이미 기록이 있어 건너뛴 수") int skipped,
    @Schema(description = "못 찾았거나 공식 오류가 난 수") int failed,
    @Schema(
            description =
                "1회 상한(12명), 공식 오류, 또는 이 방문자의 분당 공식 호출 칸을 6 남기려고 멈춰 이번에 못 한 수. 0보다 크면 1분 뒤 다시")
        int remaining,
    @Schema(description = "다시 채울 수 있는 시각(10분 쿨다운)") Instant nextAvailableAt) {
  static CardCollectionRefreshResponse from(CharacterCardRefreshService.Result result) {
    return new CardCollectionRefreshResponse(
        result.rosterCharacters(),
        result.refreshed(),
        result.skipped(),
        result.failed(),
        result.remaining(),
        result.nextAvailableAt());
  }
}
