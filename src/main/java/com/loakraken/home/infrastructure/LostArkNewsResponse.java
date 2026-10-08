package com.loakraken.home.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loakraken.home.domain.NewsFeed;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

final class LostArkNewsResponse {
  private LostArkNewsResponse() {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Notice(
      @JsonProperty("Title") String title,
      @JsonProperty("Date") LocalDateTime date,
      @JsonProperty("Link") String link,
      @JsonProperty("Type") String type) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Event(
      @JsonProperty("Title") String title,
      @JsonProperty("Thumbnail") String thumbnail,
      @JsonProperty("Link") String link,
      @JsonProperty("StartDate") LocalDateTime startDate,
      @JsonProperty("EndDate") LocalDateTime endDate,
      @JsonProperty("RewardDate") LocalDateTime rewardDate) {}

  static NewsFeed toDomain(Notice[] notices, Event[] events) {
    return new NewsFeed(
        Arrays.stream(notices)
            .filter(Objects::nonNull)
            .filter(notice -> notice.title() != null && notice.date() != null)
            .map(
                notice ->
                    new NewsFeed.Notice(
                        notice.title().strip(),
                        NewsFeed.Category.of(notice.type() == null ? null : notice.type().strip()),
                        notice.date(),
                        NewsFeed.officialUrl(notice.link())))
            .toList(),
        Arrays.stream(events)
            .filter(Objects::nonNull)
            .filter(event -> event.title() != null)
            .map(
                event ->
                    new NewsFeed.Event(
                        event.title().strip(),
                        NewsFeed.officialUrl(event.thumbnail()),
                        event.startDate(),
                        event.endDate(),
                        event.rewardDate(),
                        NewsFeed.officialUrl(event.link())))
            .toList());
  }
}
