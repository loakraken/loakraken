package com.loakraken.home.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.home.application.PatchNoteService;
import com.loakraken.home.domain.PatchNotice;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Schema(
    description =
        "패치 이력(patch-notice-v1). 공식 공지 중 제목 규칙에 맞는 패치 공지만 모은 목록입니다. 공식 API는 최근 약 3개월만 주므로 LOAKraken이 관측한 공지를 쌓아 이력을 늘립니다. 본문은 공식 원문 링크로 봅니다.")
public record HomePatchNotesResponse(
    @Schema(example = "patch-notice-v1") String ruleVersion,
    @NullableResponse
        @Schema(allowableValues = {"UPDATE", "BALANCE", "CLIENT_PATCH", "MAINTENANCE_COMPLETE"})
        String category,
    int page,
    int size,
    long total,
    @NullableResponse @Schema(description = "저장된 가장 오래된 공지의 게시 시각(KST). 이보다 앞선 이력은 아직 없습니다")
        OffsetDateTime oldestPublishedAt,
    @NullableResponse @Schema(description = "LOAKraken이 패치 공지를 처음 쌓기 시작한 시각")
        Instant firstObservedAt,
    @Schema(description = "공식 공지를 마지막으로 받은 시각(소식 캐시)") Instant sourceFetchedAt,
    @Schema(description = "게시 시각 내림차순") List<HomePatchNoticeResponse> items) {
  private static final ZoneOffset KST = ZoneOffset.ofHours(9);

  static HomePatchNotesResponse from(
      PatchNoteService.Page page, PatchNotice.Category category, int pageNo, int size) {
    var coverage = page.coverage();
    return new HomePatchNotesResponse(
        PatchNotice.RULE_VERSION,
        category == null ? null : category.name(),
        pageNo,
        size,
        page.total(),
        coverage.oldestPublishedAt() == null ? null : coverage.oldestPublishedAt().atOffset(KST),
        coverage.firstObservedAt(),
        page.sourceFetchedAt(),
        page.items().stream()
            .map(
                notice ->
                    new HomePatchNoticeResponse(
                        notice.noticeId(),
                        notice.title(),
                        notice.category().name(),
                        notice.publishedAt().atOffset(KST),
                        notice.url()))
            .toList());
  }

  public record HomePatchNoticeResponse(
      @Schema(description = "공식 공지 번호") long noticeId,
      String title,
      @Schema(allowableValues = {"UPDATE", "BALANCE", "CLIENT_PATCH", "MAINTENANCE_COMPLETE"})
          String category,
      OffsetDateTime publishedAt,
      @Schema(description = "공식 원문 링크(lostark.game.onstove.com만 허용)") String url) {}
}
