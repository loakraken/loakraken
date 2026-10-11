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
 * 회귀 점검표 B3(현금 거래 환산 금지, 사용자 10-12): 디코·현금 시세와 그 입력 칸을 다시 넣지 않는다. 운영 코드·리소스(src/main)에 용어가 있으면
 * 실패한다. 크리스탈 공식 경로만 쓴다.
 */
class RegressionGuardTest {
  /** 점검표 B3 grep 용어. */
  static final List<String> RMT_TERMS = List.of("디코 ", "100골드당", "discordWon", "디스코드 시세");

  @Test
  void 운영_코드와_리소스에_현금_거래_환산_용어가_없다() throws IOException {
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
        for (var term : RMT_TERMS) if (text.contains(term)) hits.add(file + ": " + term.strip());
      }
    }
    assertThat(hits).as("회귀 점검표 B3 RMT 용어").isEmpty();
  }
}
