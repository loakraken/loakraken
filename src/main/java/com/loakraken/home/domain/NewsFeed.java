package com.loakraken.home.domain;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** 공식 공지·이벤트. 시각은 공식 응답의 KST 지역 시각이다. */
public record NewsFeed(List<Notice> notices, List<Event> events) {
  private static final Set<String> OFFICIAL_HOSTS =
      Set.of("lostark.game.onstove.com", "cdn-lostark.game.onstove.com");

  public NewsFeed {
    notices = notices == null ? List.of() : List.copyOf(notices);
    events = events == null ? List.of() : List.copyOf(events);
  }

  public enum Category {
    NOTICE("공지"),
    MAINTENANCE("점검"),
    EVENT("이벤트"),
    SHOP("상점");

    private final String official;

    Category(String official) {
      this.official = official;
    }

    public static Category of(String official) {
      for (var category : values()) if (category.official.equals(official)) return category;
      return null;
    }
  }

  public record Notice(String title, Category category, LocalDateTime publishedAt, String url) {
    /** 제목에 "점검"이 있으면 "N월 N일" 날짜를 읽는다. 연도는 게시 시각 기준이며 연말 게시·연초 점검은 다음 해로 본다. */
    public MaintenanceDate maintenanceDate() {
      if (title == null || !title.contains("점검") || publishedAt == null) return null;
      var matcher = MONTH_DAY.matcher(title);
      if (!matcher.find()) return null;
      int month = Integer.parseInt(matcher.group(1));
      int day = Integer.parseInt(matcher.group(2));
      int year = publishedAt.getYear();
      if (month < publishedAt.getMonthValue() - 6) year++;
      try {
        return new MaintenanceDate(java.time.LocalDate.of(year, month, day), matcher.group());
      } catch (java.time.DateTimeException exception) {
        return null;
      }
    }
  }

  public record MaintenanceDate(java.time.LocalDate date, String text) {}

  private static final java.util.regex.Pattern MONTH_DAY =
      java.util.regex.Pattern.compile("(\\d{1,2})월\\s*(\\d{1,2})일(?:\\s*\\([월화수목금토일]\\))?");

  public record Event(
      String title,
      String thumbnail,
      LocalDateTime startAt,
      LocalDateTime endAt,
      LocalDateTime rewardAt,
      String url) {}

  /** 공식 도메인의 https 주소만 통과시킨다. 그 밖의 주소는 null. */
  public static String officialUrl(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      var uri = URI.create(value.strip());
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
      if (!OFFICIAL_HOSTS.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))) return null;
      if (uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
      return uri.toString();
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }
}
