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

  private static GameCalendar.Entry island(String name, LocalDate date) {
    return new GameCalendar.Entry("모험 섬", name, null, 635, List.of(at(date, 19)), List.of());
  }

  private static LocalDateTime at(LocalDate date, int hour) {
    return date.atTime(hour, 0);
  }
}
