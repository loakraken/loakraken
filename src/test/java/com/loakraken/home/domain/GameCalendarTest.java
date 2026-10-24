package com.loakraken.home.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.home.domain.GameCalendar.RewardKind;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameCalendarTest {
  private static final LocalDate MON = LocalDate.parse("2026-10-12");
  private static final LocalDate THU = LocalDate.parse("2026-10-08");

  @Test
  void 대표_보상은_그_날_회차에_시간_지정된_보상만으로_판별한다() {
    var island =
        new GameCalendar.Entry(
            "모험 섬",
            "라일라이 아일랜드",
            null,
            635,
            List.of(at(THU, 11), at(THU, 13), at(MON, 11), at(MON, 13)),
            List.of(
                new GameCalendar.Reward("실링", null, "일반", List.of()), // 매 회차 공통
                new GameCalendar.Reward("골드", null, "일반", List.of(at(MON, 11), at(MON, 13))),
                new GameCalendar.Reward("해적 주화", null, "일반", List.of(at(THU, 11))),
                new GameCalendar.Reward("전설 ~ 고급 카드 팩 IV", null, "전설", List.of(at(THU, 13)))));
    assertThat(island.rewardKindsOn(THU))
        .containsExactlyInAnyOrder(RewardKind.PIRATE_COIN, RewardKind.CARD_PACK);
    assertThat(island.rewardKindsOn(MON)).containsExactly(RewardKind.GOLD);
    assertThat(island.rewardKindsOn(LocalDate.parse("2026-10-09"))).isEmpty();
    assertThat(island.nextAt(at(THU, 12))).isEqualTo(at(THU, 13));
    assertThat(island.nextAt(at(MON, 14))).isNull();
    assertThat(island.startTimesOn(MON)).containsExactly(at(MON, 11), at(MON, 13));
  }

  @Test
  void 그_날_보상은_ItemLevel_그룹을_지키고_다른_날_회차_보상은_뺀다() {
    var gate =
        new GameCalendar.Entry(
            "카오스게이트",
            "일렁이는 악마군단",
            null,
            1580,
            List.of(at(THU, 11), at(MON, 11)),
            List.of(
                new GameCalendar.Reward("공통 재료", null, "영웅", List.of(), 1580),
                new GameCalendar.Reward("목요일 보상", null, "전설", List.of(at(THU, 11)), 1580),
                new GameCalendar.Reward("월요일 보상", null, "전설", List.of(at(MON, 11)), 1580),
                new GameCalendar.Reward("상위 공통", null, "유물", List.of(), 1640),
                new GameCalendar.Reward("상위 월요일만", null, "유물", List.of(at(MON, 11)), 1700)));
    var thursday = gate.rewardGroupsOn(THU);
    assertThat(thursday)
        .extracting(GameCalendar.RewardGroup::minItemLevel)
        .containsExactly(1580, 1640);
    assertThat(thursday.get(0).rewards())
        .containsExactly(
            new GameCalendar.DayReward("공통 재료", null, "영웅", false),
            new GameCalendar.DayReward("목요일 보상", null, "전설", true));
    assertThat(thursday.get(1).rewards())
        .containsExactly(new GameCalendar.DayReward("상위 공통", null, "유물", false));
    // 월요일에는 1700 그룹이 생기고 목요일 보상은 빠진다.
    var monday = gate.rewardGroupsOn(MON);
    assertThat(monday)
        .extracting(GameCalendar.RewardGroup::minItemLevel)
        .containsExactly(1580, 1640, 1700);
    assertThat(monday.get(0).rewards())
        .extracting(GameCalendar.DayReward::name)
        .containsExactly("공통 재료", "월요일 보상");
  }

  @Test
  void 주간_표와_범위는_날짜순이고_범주별로_이름순이다() {
    var calendar =
        new GameCalendar(
            List.of(
                island("하모니 섬", THU),
                island("기회의 섬", THU),
                island("메데이아", MON),
                new GameCalendar.Entry(
                    "카오스게이트", "애니츠", null, 1580, List.of(at(THU, 6)), List.of())));
    assertThat(calendar.coveredFrom()).isEqualTo(THU);
    assertThat(calendar.coveredTo()).isEqualTo(MON);
    var weekly = calendar.weekly();
    assertThat(weekly).extracting(GameCalendar.DayIslands::date).containsExactly(THU, MON);
    assertThat(weekly.getFirst().islands())
        .extracting(GameCalendar.IslandDay::name)
        .containsExactly("기회의 섬", "하모니 섬");
    assertThat(calendar.byCategory("카오스게이트")).hasSize(1);
    assertThat(new GameCalendar(List.of()).coveredFrom()).isNull();
  }

  @Test
  void 보상_이름_판별은_공식_이름_기준이다() {
    assertThat(RewardKind.of("골드")).isEqualTo(RewardKind.GOLD);
    assertThat(RewardKind.of("전설 ~ 고급 카드 팩 III")).isEqualTo(RewardKind.CARD_PACK);
    assertThat(RewardKind.of("실링")).isEqualTo(RewardKind.SILLING);
    assertThat(RewardKind.of("대양의 주화 상자")).isEqualTo(RewardKind.PIRATE_COIN);
    assertThat(RewardKind.of("해적 주화")).isEqualTo(RewardKind.PIRATE_COIN);
    assertThat(RewardKind.of("영혼의 잎사귀")).isNull();
    assertThat(RewardKind.of(null)).isNull();
  }

  @Test
  void 주간_종류는_덮는_날짜마다_한_줄이고_그_날_회차가_있을_때만_참이다() {
    var wed = LocalDate.parse("2026-10-21");
    var calendar =
        new GameCalendar(
            List.of(
                island("라일라이 아일랜드", wed),
                // 같은 항목이 다른 날 회차만 가지면 그 날은 거짓(목록에 있어도).
                new GameCalendar.Entry(
                    "카오스게이트",
                    "애니츠",
                    null,
                    1580,
                    List.of(at(wed, 11), at(wed.plusDays(3), 11)),
                    List.of()),
                new GameCalendar.Entry(
                    "필드보스", "세베크 아툰", null, 1460, List.of(at(wed.plusDays(3), 21)), List.of())));
    assertThat(calendar.weekKinds())
        .containsExactly(
            new GameCalendar.DayKinds(wed, true, false),
            new GameCalendar.DayKinds(wed.plusDays(1), false, false),
            new GameCalendar.DayKinds(wed.plusDays(2), false, false),
            new GameCalendar.DayKinds(wed.plusDays(3), true, true));
    assertThat(new GameCalendar(List.of()).weekKinds()).isEmpty();
  }

  private static GameCalendar.Entry island(String name, LocalDate date) {
    return new GameCalendar.Entry("모험 섬", name, null, 635, List.of(at(date, 19)), List.of());
  }

  private static LocalDateTime at(LocalDate date, int hour) {
    return date.atTime(hour, 0);
  }
}
