package com.loakraken.home.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.home.application.HomeCache;
import com.loakraken.home.domain.NewsFeed;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

@Schema(description = "공식 공지(최신순)와 진행 중 이벤트(종료 임박순). 링크·이미지는 공식 https 도메인만 통과합니다.")
public record HomeNewsResponse(
    @Schema(description = "공식 소식 수집 시각(UTC)") Instant fetchedAt,
    List<HomeNoticeResponse> notices,
    List<HomeEventResponse> events) {
  static final ZoneOffset KST = ZoneOffset.ofHours(9);

  static HomeNewsResponse from(
      HomeCache.NewsSnapshot snapshot, int limit, NewsFeed.Category category, LocalDateTime now) {
    var news = snapshot.news();
    return new HomeNewsResponse(
        snapshot.fetchedAt(),
        news.notices().stream()
            .filter(notice -> category == null || notice.category() == category)
            .sorted(Comparator.comparing(NewsFeed.Notice::publishedAt).reversed())
            .limit(limit)
            .map(HomeNewsResponse::notice)
            .toList(),
        news.events().stream()
            .filter(
                event ->
                    (event.startAt() == null || !event.startAt().isAfter(now))
                        && (event.endAt() == null || event.endAt().isAfter(now)))
            .sorted(
                Comparator.comparing(
                    NewsFeed.Event::endAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(
                event ->
                    new HomeEventResponse(
                        event.title(),
                        event.thumbnail(),
                        time(event.startAt()),
                        time(event.endAt()),
                        time(event.rewardAt()),
                        event.url()))
            .toList());
  }

  private static HomeNoticeResponse notice(NewsFeed.Notice notice) {
    var maintenance = notice.maintenanceDate();
    return new HomeNoticeResponse(
        notice.title(),
        notice.category(),
        time(notice.publishedAt()),
        notice.url(),
        maintenance == null ? null : maintenance.date(),
        maintenance == null ? null : maintenance.text());
  }

  private static OffsetDateTime time(LocalDateTime value) {
    return value == null ? null : value.atOffset(KST);
  }

  public record HomeNoticeResponse(
      String title,
      @Schema(description = "NOTICE(공지), MAINTENANCE(점검), EVENT(이벤트), SHOP(상점). 모르는 공식 분류면 null")
          @NullableResponse
          NewsFeed.Category category,
      OffsetDateTime publishedAt,
      @NullableResponse String url,
      @Schema(description = "제목에 '점검'이 있고 'N월 N일'을 읽을 수 있으면 그 날짜(KST)") @NullableResponse
          LocalDate maintenanceDate,
      @Schema(description = "maintenanceDate를 읽은 제목 원문 조각") @NullableResponse
          String maintenanceDateText) {}

  public record HomeEventResponse(
      String title,
      @NullableResponse String thumbnail,
      @NullableResponse OffsetDateTime startAt,
      @NullableResponse OffsetDateTime endAt,
      @NullableResponse OffsetDateTime rewardAt,
      @NullableResponse String url) {}
}
