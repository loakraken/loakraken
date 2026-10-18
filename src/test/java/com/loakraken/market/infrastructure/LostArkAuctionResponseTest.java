package com.loakraken.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 공식 경매 검색 응답 변환(#609): 공식 Icon이 매물 icon까지 그대로 온다. */
class LostArkAuctionResponseTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 공식 POST /auctions/items 응답 모양(쓰는 필드만, 값은 합성). */
  private static final String RESPONSE =
      """
      {"PageNo":1,"PageSize":10,"TotalCount":2,"Items":[
        {"Name":"도래한 결전의 목걸이","Grade":"고대","Tier":4,"Level":null,
         "Icon":"https://cdn-lostark.game.onstove.com/efui_iconatlas/acc/acc_215.png",
         "GradeQuality":90,
         "AuctionInfo":{"StartPrice":1000,"BuyPrice":1200,"BidPrice":0,"EndDate":"2026-10-19T00:50:43.75",
           "BidCount":0,"BidStartPrice":1000,"IsCompetitive":false,"TradeAllowCount":2,"UpgradeLevel":null},
         "Options":[{"Type":"ARK_PASSIVE","OptionName":"깨달음","OptionNameTripod":"","Value":13.0,
           "IsPenalty":false,"ClassName":"","IsValuePercentage":false}]},
        {"Name":"아이콘 없는 매물","Grade":"고대","GradeQuality":80,
         "AuctionInfo":{"BuyPrice":500,"BidPrice":0,"BidStartPrice":500,"EndDate":"2026-10-19T01:00:00",
           "TradeAllowCount":1},
         "Options":[]}
      ]}
      """;

  @Test
  void 공식_Icon이_매물_icon까지_온다() throws Exception {
    var page = MAPPER.readValue(RESPONSE, LostArkAuctionResponse.class).toPage(Instant.EPOCH);
    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(0).icon())
        .isEqualTo("https://cdn-lostark.game.onstove.com/efui_iconatlas/acc/acc_215.png");
    assertThat(page.items().get(0).buyPrice()).isEqualTo(1200L);
    assertThat(page.items().get(0).options()).hasSize(1);
    // 공식 응답에 Icon이 없으면 null(지어내지 않음).
    assertThat(page.items().get(1).icon()).isNull();
  }
}
