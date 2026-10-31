package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 휘장 주소 정규화: 공식 이미지 서버의 https 주소만, 호스트 소문자, 쿼리·조각 없이(통계 묶음 기준). */
class EmblemUrlTest {
  private static final String URL =
      "https://cdn-lostark.game.onstove.com/efui_iconatlas/playerinfodecobrooch/playerinfodecobrooch_97.png";

  @Test
  void 공식_주소는_쿼리와_조각을_빼고_호스트를_소문자로_같게_만든다() {
    assertThat(PostgresCharacterHistoryRepository.emblemUrl(URL)).isEqualTo(URL);
    assertThat(PostgresCharacterHistoryRepository.emblemUrl("  " + URL + "?v=2#a  "))
        .isEqualTo(URL);
    assertThat(
            PostgresCharacterHistoryRepository.emblemUrl(
                URL.replace("cdn-lostark.game.onstove.com", "CDN-LOSTARK.GAME.ONSTOVE.COM")))
        .isEqualTo(URL);
  }

  @Test
  void 공식_서버가_아니거나_https가_아니거나_비면_버린다() {
    assertThat(PostgresCharacterHistoryRepository.emblemUrl(URL.replace("https:", "http:")))
        .isNull();
    assertThat(PostgresCharacterHistoryRepository.emblemUrl("https://example.com/x.png")).isNull();
    assertThat(
            PostgresCharacterHistoryRepository.emblemUrl(
                "https://cdn-lostark.game.onstove.com.example.com/x.png"))
        .isNull();
    assertThat(
            PostgresCharacterHistoryRepository.emblemUrl(
                "https://cdn-lostark.game.onstove.com:8443/x.png"))
        .isNull();
    assertThat(PostgresCharacterHistoryRepository.emblemUrl("https://cdn-lostark.game.onstove.com"))
        .isNull();
    assertThat(PostgresCharacterHistoryRepository.emblemUrl("not a url")).isNull();
    assertThat(PostgresCharacterHistoryRepository.emblemUrl(" ")).isNull();
    assertThat(PostgresCharacterHistoryRepository.emblemUrl(null)).isNull();
  }
}
