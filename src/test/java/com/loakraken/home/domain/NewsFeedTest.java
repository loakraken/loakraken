package com.loakraken.home.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NewsFeedTest {
  @Test
  void 제목에_점검이_있으면_월일을_게시_연도로_읽고_연말_게시_연초_점검은_다음_해다() {
    var notice = notice("10월 9일(금) 로스트아크 임시 점검 안내", "2026-10-08T21:00:04");
    assertThat(notice.maintenanceDate().date()).isEqualTo(LocalDate.parse("2026-10-09"));
    assertThat(notice.maintenanceDate().text()).isEqualTo("10월 9일(금)");
    assertThat(notice("1월 7일(수) 정기 점검 안내", "2026-12-30T10:00:00").maintenanceDate().date())
        .isEqualTo(LocalDate.parse("2027-01-07"));
    assertThat(notice("10월 7일(수) 업데이트 내역 안내", "2026-10-07T09:59:53").maintenanceDate()).isNull();
    assertThat(notice("긴급 점검 안내", "2026-10-07T09:59:53").maintenanceDate()).isNull();
    assertThat(notice("2월 30일 점검 안내", "2026-02-01T09:00:00").maintenanceDate()).isNull();
  }

  @Test
  void 공식_https_도메인만_통과한다() {
    assertThat(NewsFeed.officialUrl("https://lostark.game.onstove.com/News/Notice/Views/13568"))
        .isEqualTo("https://lostark.game.onstove.com/News/Notice/Views/13568");
    assertThat(NewsFeed.officialUrl(" https://cdn-lostark.game.onstove.com/uploadfiles/a.jpg "))
        .isEqualTo("https://cdn-lostark.game.onstove.com/uploadfiles/a.jpg");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://lostark.game.onstove.com/a",
        "https://evil.example.com/a",
        "https://lostark.game.onstove.com.evil.com/a",
        "https://user@lostark.game.onstove.com/a",
        "https://lostark.game.onstove.com:8443/a",
        "javascript:alert(1)",
        "not a url",
        ""
      })
  void 공식_도메인이_아니면_null이다(String value) {
    assertThat(NewsFeed.officialUrl(value)).isNull();
  }

  @Test
  void 공식_분류를_변환하고_모르는_분류는_null이다() {
    assertThat(NewsFeed.Category.of("점검")).isEqualTo(NewsFeed.Category.MAINTENANCE);
    assertThat(NewsFeed.Category.of("상점")).isEqualTo(NewsFeed.Category.SHOP);
    assertThat(NewsFeed.Category.of("기타")).isNull();
  }

  private static NewsFeed.Notice notice(String title, String publishedAt) {
    return new NewsFeed.Notice(
        title, NewsFeed.Category.NOTICE, LocalDateTime.parse(publishedAt), null);
  }
}
