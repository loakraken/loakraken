package com.loakraken.home.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.home.domain.GameCalendar;
import com.loakraken.home.domain.NewsFeed;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LostArkHomeResponseTest {
  private final JsonMapper mapper = new JsonMapper();

  @Test
  void 공식_일정_응답을_도메인으로_바꾸고_보상_그룹을_펼친다() throws Exception {
    var json =
        """
        [{"CategoryName":"모험 섬","ContentsName":"라일라이 아일랜드","ContentsIcon":"https://cdn-lostark.game.onstove.com/i.png",
          "MinItemLevel":635,"StartTimes":["2026-10-12T13:00:00","2026-10-12T11:00:00"],"Location":"x",
          "RewardItems":[{"ItemLevel":0,"Items":[{"Name":"골드","Icon":null,"Grade":"일반","StartTimes":["2026-10-12T11:00:00"]},
            {"Name":"실링","Icon":null,"Grade":"일반","StartTimes":null}]},null]},
         {"CategoryName":"필드보스","ContentsName":"세베크 아툰","StartTimes":null,"RewardItems":null},
         {"CategoryName":null,"ContentsName":"이름만"},null]
        """;
    var calendar =
        LostArkCalendarResponse.toDomain(mapper.readValue(json, LostArkCalendarResponse[].class));
    assertThat(calendar.entries()).hasSize(2);
    var island = calendar.entries().getFirst();
    assertThat(island.startTimes())
        .containsExactly(
            LocalDateTime.parse("2026-10-12T11:00:00"), LocalDateTime.parse("2026-10-12T13:00:00"));
    assertThat(island.rewards()).hasSize(2);
    assertThat(island.rewardKindsOn(LocalDate.parse("2026-10-12")))
        .containsExactly(GameCalendar.RewardKind.GOLD);
    assertThat(calendar.entries().get(1).startTimes()).isEmpty();
  }

  @Test
  void 공식_공지와_이벤트를_변환하고_공식_도메인_밖_링크는_버린다() throws Exception {
    var notices =
        mapper.readValue(
            """
            [{"Title":" 10월 9일(금) 로스트아크 임시 점검 안내 ","Date":"2026-10-08T21:00:04.323",
              "Link":"https://lostark.game.onstove.com/News/Notice/Views/13568","Type":"공지"},
             {"Title":"외부","Date":"2026-10-08T10:00:00","Link":"https://evil.example.com","Type":"점검"},
             {"Title":null,"Date":"2026-10-08T10:00:00"}]
            """,
            LostArkNewsResponse.Notice[].class);
    var events =
        mapper.readValue(
            """
            [{"Title":"핫타임","Thumbnail":"https://cdn-lostark.game.onstove.com/b.jpg",
              "Link":"https://lostark.game.onstove.com/News/Notice/Views/13557",
              "StartDate":"2026-10-03T06:00:00","EndDate":"2026-10-12T06:00:00","RewardDate":null}]
            """,
            LostArkNewsResponse.Event[].class);
    var news = LostArkNewsResponse.toDomain(notices, events);
    assertThat(news.notices()).hasSize(2);
    assertThat(news.notices().getFirst().title()).isEqualTo("10월 9일(금) 로스트아크 임시 점검 안내");
    assertThat(news.notices().getFirst().category()).isEqualTo(NewsFeed.Category.NOTICE);
    assertThat(news.notices().get(1).url()).isNull();
    assertThat(news.notices().get(1).category()).isEqualTo(NewsFeed.Category.MAINTENANCE);
    assertThat(news.events().getFirst().rewardAt()).isNull();
    assertThat(news.events().getFirst().thumbnail()).startsWith("https://cdn-lostark");
  }
}
