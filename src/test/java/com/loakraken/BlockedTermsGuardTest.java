package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 공개 저장소 규칙(2026-10-12): 추적하는 공개 파일(운영 코드·리소스·문서·배포 설정)에 금지 이름이 없다. 이름 목록은 {@link SiteNames}가 저장소
 * 밖에서 읽고, 없으면 이 검사는 건너뛴다. 실패해도 일치한 파일·줄 번호만 보이고 이름은 출력하지 않는다.
 *
 * <p>예외: 데이터 리소스 JSON의 출처 값(키 {@code source}와 {@code …Source}, 그 안의 값), 이미 적용된 Flyway 마이그레이션(바꾸면
 * 체크섬이 달라진다).
 */
class BlockedTermsGuardTest {
  private static final List<String> SCOPE =
      List.of(
          "src/main/",
          "docs/",
          "deploy/",
          ".github/",
          "README.md",
          "NOTICE",
          "build.gradle",
          "settings.gradle",
          "compose.yaml",
          "Dockerfile",
          ".env.example");
  private static final List<String> EXCLUDED = List.of("src/main/resources/db/migration/");
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void 공개_파일에_금지_이름이_없다() throws Exception {
    assumeThat(SiteNames.BLOCKED).as("금지 이름 목록 없음(비밀값·로컬 파일) — 건너뜀").isNotEmpty();
    var patterns = SiteNames.BLOCKED.stream().map(SiteNames::pattern).toList();
    var hits = new ArrayList<String>();
    for (var file : trackedFiles()) {
      if (SCOPE.stream().noneMatch(file::startsWith)
          || EXCLUDED.stream().anyMatch(file::startsWith)
          || !Files.isRegularFile(Path.of(file))
          || binary(file)) continue;
      var text = Files.readString(Path.of(file), StandardCharsets.UTF_8);
      if (file.endsWith(".json")) {
        var node = MAPPER.readTree(text);
        stripSources(node);
        var values = MAPPER.writeValueAsString(node);
        if (patterns.stream().anyMatch(pattern -> pattern.matcher(values).find()))
          hits.add(file + " (출처 밖 JSON 값)");
        continue;
      }
      var lines = text.split("\\R", -1);
      for (int i = 0; i < lines.length; i++) {
        var line = lines[i];
        if (patterns.stream().anyMatch(pattern -> pattern.matcher(line).find()))
          hits.add(file + ":" + (i + 1));
      }
    }
    assertThat(hits).as("금지 이름이 있는 위치(이름은 출력하지 않음)").isEmpty();
  }

  private static void stripSources(JsonNode node) {
    if (node instanceof ObjectNode object) {
      var keys = new ArrayList<String>();
      for (Map.Entry<String, JsonNode> entry : object.properties()) keys.add(entry.getKey());
      for (var key : keys) {
        if (key.equals("source") || key.endsWith("Source")) object.remove(key);
        else stripSources(object.get(key));
      }
    } else if (node != null && node.isArray()) for (var item : node) stripSources(item);
  }

  private static boolean binary(String file) {
    return file.matches("(?i).*\\.(png|jpe?g|gif|ico|jar|woff2?|ttf)$");
  }

  private static List<String> trackedFiles() throws IOException, InterruptedException {
    var process = new ProcessBuilder("git", "ls-files").redirectErrorStream(true).start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as("git ls-files").isZero();
    return output.lines().filter(line -> !line.isBlank()).toList();
  }
}
