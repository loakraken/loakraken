package com.loakraken.home.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.home.application.HomeCache;
import com.loakraken.home.domain.GameCalendar;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

@Schema(description = "공식 이번 주 일정 중 요청 날짜(KST) 보기. 시각은 +09:00 오프셋입니다.")
public record HomeScheduleResponse(
    String ruleVersion,
    @Schema(description = "요청 날짜(KST)") LocalDate date,
    String timezone,
    @Schema(description = "공식 일정 수집 시각(UTC)") Instant fetchedAt,
    @Schema(description = "공식 일정이 덮는 첫 날짜. 일정이 없으면 null") @NullableResponse LocalDate coveredFrom,
    @NullableResponse LocalDate coveredTo,
    @Schema(description = "그 날 열리는 모험 섬") List<HomeIslandResponse> adventureIslands,
    List<HomeTimedResponse> fieldBosses,
    @Schema(description = "카오스게이트(지역별 공식 항목 그대로)") List<HomeTimedResponse> chaosGates,
    @Schema(description = "섬·항해·로웬·태초의 섬 등 그 날 회차가 있는 기타 일정") List<HomeOtherResponse> others,
    @Schema(description = "이번 주 골드 회차가 있는 날") List<HomeGoldDayResponse> goldIslandDays,
    @Schema(description = "이번 주 날짜별 모험 섬") List<HomeWeeklyDayResponse> weekly) {
  public static final String RULE_VERSION = "home-schedule-v1";
  static final ZoneOffset KST = ZoneOffset.ofHours(9);

  static HomeScheduleResponse from(
      HomeCache.CalendarSnapshot snapshot, LocalDate date, LocalDateTime now) {
    var calendar = snapshot.calendar();
    var weekly = calendar.weekly();
    return new HomeScheduleResponse(
        RULE_VERSION,
        date,
        "Asia/Seoul",
        snapshot.fetchedAt(),
        calendar.coveredFrom(),
        calendar.coveredTo(),
        calendar.byCategory(GameCalendar.ADVENTURE_ISLAND).stream()
            .filter(entry -> !entry.startTimesOn(date).isEmpty())
            .map(
                entry ->
                    new HomeIslandResponse(
                        entry.name(),
                        entry.icon(),
                        entry.minItemLevel(),
                        times(entry.startTimesOn(date)),
                        entry.rewardKindsOn(date),
                        groups(entry, date),
                        time(entry.nextAt(now)),
                        entry.location()))
            .toList(),
        timed(calendar.byCategory(GameCalendar.FIELD_BOSS), date, now),
        timed(calendar.byCategory(GameCalendar.CHAOS_GATE), date, now),
        calendar.entries().stream()
            .filter(
                entry ->
                    !entry.category().equals(GameCalendar.ADVENTURE_ISLAND)
                        && !entry.category().equals(GameCalendar.FIELD_BOSS)
                        && !entry.category().equals(GameCalendar.CHAOS_GATE)
                        && !entry.startTimesOn(date).isEmpty())
            .sorted(
                java.util.Comparator.comparing(GameCalendar.Entry::category)
                    .thenComparing(GameCalendar.Entry::name))
            .map(
                entry ->
                    new HomeOtherResponse(
                        entry.category(),
                        entry.name(),
                        entry.icon(),
                        entry.minItemLevel(),
                        times(entry.startTimesOn(date)),
                        groups(entry, date),
                        time(entry.nextAt(now)),
                        entry.location()))
            .toList(),
        weekly.stream()
            .map(
                day ->
                    new HomeGoldDayResponse(
                        day.date(),
                        day.islands().stream()
                            .filter(
                                island ->
                                    island.rewardKinds().contains(GameCalendar.RewardKind.GOLD))
                            .map(GameCalendar.IslandDay::name)
                            .toList()))
            .filter(day -> !day.islands().isEmpty())
            .toList(),
        weekly.stream()
            .map(
                day ->
                    new HomeWeeklyDayResponse(
                        day.date(),
                        day.islands().stream()
                            .map(
                                island ->
                                    new HomeWeeklyIslandResponse(
                                        island.name(), island.rewardKinds()))
                            .toList()))
            .toList());
  }

  private static List<HomeTimedResponse> timed(
      List<GameCalendar.Entry> entries, LocalDate date, LocalDateTime now) {
    return entries.stream()
        .map(
            entry ->
                new HomeTimedResponse(
                    entry.name(),
                    entry.icon(),
                    entry.minItemLevel(),
                    times(entry.startTimesOn(date)),
                    groups(entry, date),
                    time(entry.nextAt(now)),
                    entry.location()))
        .toList();
  }

  private static List<HomeRewardGroupResponse> groups(GameCalendar.Entry entry, LocalDate date) {
    return entry.rewardGroupsOn(date).stream()
        .map(
            group ->
                new HomeRewardGroupResponse(
                    group.minItemLevel(),
                    group.rewards().stream()
                        .map(
                            reward ->
                                new HomeRewardResponse(
                                    reward.name(), reward.icon(), reward.grade(), reward.timed()))
                        .toList()))
        .toList();
  }

  private static List<OffsetDateTime> times(List<LocalDateTime> values) {
    return values.stream().map(value -> value.atOffset(KST)).toList();
  }

  private static OffsetDateTime time(LocalDateTime value) {
    return value == null ? null : value.atOffset(KST);
  }

  public record HomeIslandResponse(
      String name,
      @NullableResponse String icon,
      @NullableResponse Integer minItemLevel,
      List<OffsetDateTime> startTimes,
      @Schema(description = "그 날 회차에 시간 지정으로 붙은 대표 보상(GOLD, CARD_PACK, SILLING, PIRATE_COIN)")
          Set<GameCalendar.RewardKind> rewardKinds,
      @Schema(
              description =
                  "그 날 보상. 공식 ItemLevel 그룹 순서이며, 시간 지정 보상은 그 날 회차를 가리킬 때만 넣는다(timed=true)")
          List<HomeRewardGroupResponse> rewardGroups,
      @Schema(description = "현재 이후 첫 회차(이번 주 범위). 없으면 null") @NullableResponse
          OffsetDateTime nextAt,
      @Schema(description = "공식 위치(Location). 없으면 null", example = "레갸르방크 대평원") @NullableResponse
          String location) {}

  public record HomeTimedResponse(
      String name,
      @NullableResponse String icon,
      @NullableResponse Integer minItemLevel,
      @Schema(description = "요청 날짜의 회차") List<OffsetDateTime> startTimes,
      @Schema(
              description =
                  "그 날 보상. 공식 ItemLevel 그룹 순서이며, 시간 지정 보상은 그 날 회차를 가리킬 때만 넣는다(timed=true)")
          List<HomeRewardGroupResponse> rewardGroups,
      @NullableResponse OffsetDateTime nextAt,
      @Schema(description = "공식 위치(Location). 없으면 null", example = "레갸르방크 대평원") @NullableResponse
          String location) {}

  public record HomeOtherResponse(
      @Schema(description = "공식 범주 이름(섬, 항해, 로웬, 태초의 섬 등)") String category,
      String name,
      @NullableResponse String icon,
      @NullableResponse Integer minItemLevel,
      List<OffsetDateTime> startTimes,
      @Schema(
              description =
                  "그 날 보상. 공식 ItemLevel 그룹 순서이며, 시간 지정 보상은 그 날 회차를 가리킬 때만 넣는다(timed=true)")
          List<HomeRewardGroupResponse> rewardGroups,
      @NullableResponse OffsetDateTime nextAt,
      @Schema(description = "공식 위치(Location). 없으면 null", example = "레갸르방크 대평원") @NullableResponse
          String location) {}

  public record HomeRewardGroupResponse(
      @NullableResponse @Schema(description = "공식 ItemLevel. 이 레벨 이상 보상") Integer minItemLevel,
      List<HomeRewardResponse> rewards) {}

  public record HomeRewardResponse(
      String name,
      @NullableResponse String icon,
      @NullableResponse @Schema(description = "공식 등급 문자열") String grade,
      @Schema(description = "true: 그 날 회차를 가리키는 시간 지정 보상, false: 시간 지정 없는 공통 보상") boolean timed) {}

  public record HomeGoldDayResponse(LocalDate date, List<String> islands) {}

  public record HomeWeeklyDayResponse(LocalDate date, List<HomeWeeklyIslandResponse> islands) {}

  public record HomeWeeklyIslandResponse(String name, Set<GameCalendar.RewardKind> rewardKinds) {}
}
