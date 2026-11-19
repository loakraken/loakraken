package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * CVE-2026-114938 완화 플래그(-XX:-UseBASE64Intrinsics)가 이미지와 운영 배포 설정에서 조용히 빠지지 않는다. 고친 JDK 이미지로 올릴 때
 * 플래그와 이 테스트를 함께 뺀다.
 */
class JvmBase64IntrinsicsFlagTest {
  static final String FLAG = "-XX:-UseBASE64Intrinsics";

  /** 설명 줄(#)을 뺀, JAVA_TOOL_OPTIONS를 정하는 줄. */
  private static List<String> optionLines(Path file) throws IOException {
    return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
        .map(String::strip)
        .filter(line -> !line.startsWith("#"))
        .filter(line -> line.contains("JAVA_TOOL_OPTIONS"))
        .toList();
  }

  @Test
  void 이미지_기본_JVM_옵션에_Base64_내장_최적화_끄기가_있다() throws IOException {
    var lines = optionLines(Path.of("Dockerfile"));
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0)).startsWith("ENV JAVA_TOOL_OPTIONS=").contains(FLAG);
  }

  @Test
  void 운영_배포의_모든_JVM_옵션_줄에_있고_API_JAVA_OPTS로_바꿔도_남는다() throws IOException {
    var lines = optionLines(Path.of("deploy/compose.prod.yaml"));
    // api와 migrate 두 곳(새 서비스가 늘어도 모두 지녀야 한다).
    assertThat(lines).hasSizeGreaterThanOrEqualTo(2).allMatch(line -> line.contains(FLAG));
    // 운영자가 API_JAVA_OPTS를 정하면 치환 기본값은 버려진다: 플래그는 ${...} 밖에 있어야 한다.
    var api = lines.stream().filter(line -> line.contains("API_JAVA_OPTS")).toList();
    assertThat(api).hasSize(1);
    assertThat(api.get(0).replaceAll("\\$\\{[^}]*}", "")).contains(FLAG);
  }

  @Test
  void 다른_완화나_다시_켜기는_없다() throws IOException {
    var files =
        new java.util.ArrayList<>(
            List.of(Path.of("Dockerfile"), Path.of("deploy/compose.prod.yaml")));
    try (var listed = Files.list(Path.of("deploy"))) {
      listed
          .filter(f -> f.getFileName().toString().matches("stack\\..+\\.env\\.example"))
          .forEach(files::add);
    }
    for (var file : files)
      // AVX 전체를 낮추는 -XX:UseAVX=2는 쓰지 않는다(성능 비용, 결정 11-20).
      assertThat(Files.readString(file, StandardCharsets.UTF_8))
          .as(file.toString())
          .doesNotContain("+UseBASE64Intrinsics")
          .doesNotContain("UseAVX");
  }
}
