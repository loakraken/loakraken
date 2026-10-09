package com.loakraken.home.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.home.domain.PatchNotice.Category;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PatchNoticeTest {
  private static final LocalDateTime AT = LocalDateTime.parse("2026-10-07T10:00:00");

  @Test
  void 제목_규칙으로_패치_공지만_고른다() {
    // 2026-10-09 공식 /news/notices 실제 제목
    assertThat(Category.of("10월 7일(수) 업데이트 내역 안내")).contains(Category.UPDATE);
    assertThat(Category.of("9월 9일(수) 업데이트 내역 안내 (내용 추가)")).contains(Category.UPDATE);
    assertThat(Category.of("10월 8일(목) 클라이언트 패치 안내")).contains(Category.CLIENT_PATCH);
    assertThat(Category.of("10월 9일(금) 로스트아크 임시 점검 완료 안내")).contains(Category.MAINTENANCE_COMPLETE);
    assertThat(Category.of("9월 30일(수) 로스트아크 정기 점검 완료 안내")).contains(Category.MAINTENANCE_COMPLETE);
    assertThat(Category.of("클래스 밸런스 조정 안내")).contains(Category.BALANCE);
    // 패치가 아닌 공지
    assertThat(Category.of("10월 7일(수) 정기 점검 안내")).isEmpty();
    assertThat(Category.of("10월 9일(금) 로스트아크 임시 점검 안내")).isEmpty();
    assertThat(Category.of("9월 23일(수) 신규 상품 및 확률 안내")).isEmpty();
    assertThat(Category.of("알려진 이슈를 안내해 드립니다. (10/9 수정)")).isEmpty();
    assertThat(Category.of("리샤의 편지에서 '카제로스 레이드 익스트림'과 가을 이벤트 소식을 전합니다.")).isEmpty();
    assertThat(Category.of("불량 이용자 조치내역 안내")).isEmpty();
    assertThat(Category.of(null)).isEmpty();
  }

  @Test
  void 공식_공지_주소에서_공지_번호를_읽고_공식_도메인만_받는다() {
    var notice =
        PatchNotice.from(
                new NewsFeed.Notice(
                    " 10월 7일(수) 업데이트 내역 안내 ",
                    NewsFeed.Category.NOTICE,
                    AT,
                    "https://lostark.game.onstove.com/News/Notice/Views/13565"))
            .orElseThrow();
    assertThat(notice.noticeId()).isEqualTo(13565);
    assertThat(notice.title()).isEqualTo("10월 7일(수) 업데이트 내역 안내");
    assertThat(notice.category()).isEqualTo(Category.UPDATE);
    assertThat(
            PatchNotice.from(
                new NewsFeed.Notice(
                    "10월 7일(수) 업데이트 내역 안내",
                    NewsFeed.Category.NOTICE,
                    AT,
                    "https://evil.example/News/Notice/Views/1")))
        .isEmpty();
    assertThat(
            PatchNotice.from(
                new NewsFeed.Notice(
                    "10월 7일(수) 업데이트 내역 안내",
                    NewsFeed.Category.NOTICE,
                    AT,
                    "https://lostark.game.onstove.com/News/Event/Views/1")))
        .isEmpty();
    assertThat(PatchNotice.from(new NewsFeed.Notice("업데이트 내역 안내", null, null, null))).isEmpty();
  }
}
