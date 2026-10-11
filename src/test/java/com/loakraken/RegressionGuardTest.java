package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 회귀 점검표 B2·B3(사용자 10-12). B2: 애매한 단서 문구("다를 수 있")를 쓰지 않는다(Swagger @Operation·@Schema 설명 포함). B3:
 * 디코·현금 시세와 그 입력 칸을 다시 넣지 않는다(크리스탈 공식 경로만). 운영 코드·리소스(src/main)에 용어가 있으면 실패한다.
 */
class RegressionGuardTest {
  /** 점검표 B3 grep 용어. */
  static final List<String> RMT_TERMS = List.of("디코 ", "100골드당", "discordWon", "디스코드 시세");

  /** 점검표 B2 grep 용어. */
  static final List<String> VAGUE_TERMS = List.of("다를 수 있");

  @Test
  void 운영_코드와_리소스에_현금_거래_환산_용어가_없다() throws IOException {
    assertThat(hits(RMT_TERMS)).as("회귀 점검표 B3 RMT 용어").isEmpty();
  }

  @Test
  void 운영_코드와_Swagger_설명에_애매한_단서_문구가_없다() throws IOException {
    assertThat(hits(VAGUE_TERMS)).as("회귀 점검표 B2 단서 문구").isEmpty();
  }

  private static List<String> hits(List<String> terms) throws IOException {
    var hits = new ArrayList<String>();
    try (Stream<Path> files = Files.walk(Path.of("src", "main"))) {
      for (var file : files.filter(Files::isRegularFile).toList()) {
        var name = file.getFileName().toString();
        if (!(name.endsWith(".java")
            || name.endsWith(".json")
            || name.endsWith(".yaml")
            || name.endsWith(".yml")
            || name.endsWith(".sql")
            || name.endsWith(".properties"))) continue;
        var text = Files.readString(file, StandardCharsets.UTF_8);
        for (var term : terms) if (text.contains(term)) hits.add(file + ": " + term.strip());
      }
    }
    return hits;
  }
}
