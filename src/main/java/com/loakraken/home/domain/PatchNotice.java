package com.loakraken.home.domain;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 패치 이력 1단계(patch-notice-v1). 공식 `/news/notices` 제목으로 패치 공지만 고른다. 본문은 수집하지 않는다(2단계, 이용약관 확인 후).
 *
 * <p>공식 API는 최근 약 3개월(2026-10-09 확인: 70건, 2026-07-09~)만 준다. 관측한 패치 공지를 공지 번호로 중복 제거해 쌓아 이력을 늘린다.
 * 분류하지 못한 공지는 패치가 아니므로 저장하지 않는다(홈 소식 API에는 그대로 나온다).
 */
public record PatchNotice(
    long noticeId, String title, Category category, LocalDateTime publishedAt, String url) {
  public static final String RULE_VERSION = "patch-notice-v1";
  private static final Pattern NOTICE_ID =
      Pattern.compile(
          "^https://lostark\\.game\\.onstove\\.com/News/Notice/Views/(\\d+)(?:[?#].*)?$");

  /** 제목 규칙. 위에서부터 먼저 맞는 것을 쓴다. */
  public enum Category {
    /** "업데이트 내역 안내"(내용 추가 포함) */
    UPDATE,
    /** "밸런스" */
    BALANCE,
    /** "클라이언트 패치 안내" */
    CLIENT_PATCH,
    /** "점검 완료 안내"(정기·임시) */
    MAINTENANCE_COMPLETE;

    public static Optional<Category> of(String title) {
      if (title == null) return Optional.empty();
      String value = title.strip();
      if (value.contains("업데이트 내역 안내")) return Optional.of(UPDATE);
      if (value.contains("밸런스")) return Optional.of(BALANCE);
      if (value.contains("클라이언트 패치 안내")) return Optional.of(CLIENT_PATCH);
      if (value.contains("점검 완료 안내")) return Optional.of(MAINTENANCE_COMPLETE);
      return Optional.empty();
    }
  }

  /** 공식 공지면 패치 공지로 바꾼다. 분류 밖이거나 공식 공지 주소(번호)가 아니면 empty. */
  public static Optional<PatchNotice> from(NewsFeed.Notice notice) {
    if (notice == null || notice.publishedAt() == null) return Optional.empty();
    var category = Category.of(notice.title());
    if (category.isEmpty()) return Optional.empty();
    var url = NewsFeed.officialUrl(notice.url());
    if (url == null) return Optional.empty();
    var matcher = NOTICE_ID.matcher(url);
    if (!matcher.matches()) return Optional.empty();
    return Optional.of(
        new PatchNotice(
            Long.parseLong(matcher.group(1)),
            notice.title().strip(),
            category.get(),
            notice.publishedAt(),
            url));
  }
}
