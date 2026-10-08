package com.loakraken.home.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loakraken.home.domain.GameCalendar;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
record LostArkCalendarResponse(
    @JsonProperty("CategoryName") String categoryName,
    @JsonProperty("ContentsName") String contentsName,
    @JsonProperty("ContentsIcon") String contentsIcon,
    @JsonProperty("MinItemLevel") Integer minItemLevel,
    @JsonProperty("StartTimes") List<LocalDateTime> startTimes,
    @JsonProperty("RewardItems") List<RewardGroup> rewardItems) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record RewardGroup(@JsonProperty("Items") List<RewardItem> items) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record RewardItem(
      @JsonProperty("Name") String name,
      @JsonProperty("Icon") String icon,
      @JsonProperty("Grade") String grade,
      @JsonProperty("StartTimes") List<LocalDateTime> startTimes) {}

  static GameCalendar toDomain(LostArkCalendarResponse[] items) {
    return new GameCalendar(
        Arrays.stream(items)
            .filter(Objects::nonNull)
            .filter(item -> item.categoryName() != null && item.contentsName() != null)
            .map(
                item ->
                    new GameCalendar.Entry(
                        item.categoryName().strip(),
                        item.contentsName().strip(),
                        item.contentsIcon(),
                        item.minItemLevel(),
                        item.startTimes(),
                        item.rewardItems() == null
                            ? List.of()
                            : item.rewardItems().stream()
                                .filter(group -> group != null && group.items() != null)
                                .flatMap(group -> group.items().stream())
                                .filter(reward -> reward != null && reward.name() != null)
                                .map(
                                    reward ->
                                        new GameCalendar.Reward(
                                            reward.name().strip(),
                                            reward.icon(),
                                            reward.grade(),
                                            reward.startTimes()))
                                .toList()))
            .toList());
  }
}
