package com.loakraken.stats.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.SiteNames;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class TitleCatalogTest {
  private static final Set<String> GRADES = Set.of("일반", "고급", "희귀", "영웅", "전설", "유물", "고대", "에스더");
  private static final String OFFICIAL = "https://lostark.game.onstove.com/";

  private static String raw() throws IOException {
    try (var input = new ClassPathResource("stats/title-catalog.json").getInputStream()) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static TitleCatalog catalog() throws IOException {
    return JsonMapper.builder().build().readValue(raw(), TitleCatalog.class);
  }

  /** 공개 저장소 규칙: 줄마다 출처는 공식 주소 또는 '내부 기록'만, 다른 사이트 이름은 파일 어디에도 없다. */
  @Test
  void 출처는_공식_주소나_내부_기록뿐이고_다른_사이트_이름이_없다() throws IOException {
    for (var entry : catalog().titles())
      assertThat(entry.source().equals("내부 기록") || entry.source().startsWith(OFFICIAL))
          .as(entry.title() + " " + entry.source())
          .isTrue();
    SiteNames.assertAbsent(raw());
  }

  @Test
  void 칭호는_한_번씩이고_등급은_문서에_적은_값이며_상위_칭호는_표_안에_있다() throws IOException {
    var catalog = catalog();
    var seen = new HashSet<String>();
    for (var entry : catalog.titles()) {
      assertThat(seen.add(entry.title())).as(entry.title()).isTrue();
      assertThat(entry.title()).isNotBlank().isEqualTo(entry.title().strip());
      assertThat(GRADES).as(entry.title()).contains(entry.grade());
    }
    for (var entry : catalog.titles())
      if (entry.parentTitle() != null)
        assertThat(seen).as(entry.title()).contains(entry.parentTitle());
    assertThat(catalog.index()).hasSize(catalog.titles().size());
    // 칭호 문자열은 공식 표기 그대로 정확히 맞춘다(공백·대소문자 정리 없음).
    assertThat(catalog.index().get("빛을 꺼트리는 자").grade()).isEqualTo("희귀");
    assertThat(catalog.index()).doesNotContainKey("빛을꺼트리는자");
  }
}
