package com.loakraken.stats.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class ClassEngravingCatalogTest {
  private final ClassEngravingCatalog catalog =
      new ClassEngravingCatalog(
          Map.of("classes", "검증"),
          Map.of("검증직업", List.of("극의: 체술", "충격 단련"), "다른직업", List.of("광기")));

  @Test
  void 공백은_비교하지_않고_목록의_표기로_돌려준다() {
    assertThat(catalog.canonical("검증직업", "극의 : 체술")).contains("극의: 체술");
    assertThat(catalog.canonical("검증직업", " 충격단련 ")).contains("충격 단련");
  }

  @Test
  void 직업이_없거나_다른_직업의_직업_각인이면_빈_값이다() {
    assertThat(catalog.canonical(null, "광기")).isEmpty();
    assertThat(catalog.canonical("검증직업", "광기")).isEmpty();
    assertThat(catalog.canonical("모르는직업", "광기")).isEmpty();
    assertThat(catalog.of("모르는직업")).isEmpty();
    assertThat(catalog.of(null)).isEmpty();
  }

  @Test
  void 리소스는_출처가_있고_직업마다_직업_각인이_둘이며_공백을_빼도_겹치지_않는다() throws Exception {
    ClassEngravingCatalog resource;
    try (var input = new ClassPathResource("stats/class-engravings.json").getInputStream()) {
      resource = JsonMapper.builder().build().readValue(input, ClassEngravingCatalog.class);
    }
    assertThat(resource.source()).isNotEmpty();
    assertThat(resource.classes()).isNotEmpty();
    var all = new HashSet<String>();
    resource
        .classes()
        .forEach(
            (className, titles) -> {
              assertThat(titles).as(className).hasSize(2);
              for (var title : titles) {
                assertThat(title).as(className).isNotBlank().isEqualTo(title.strip());
                assertThat(all.add(ClassEngravingCatalog.compact(title))).as(title).isTrue();
              }
            });
  }
}
