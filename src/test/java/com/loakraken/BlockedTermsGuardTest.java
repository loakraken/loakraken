package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 공개 저장소 규칙(2026-10-12, #535): git이 추적하는 모든 파일(코드·리소스·표본·문서·설정, JSON의 출처 값 포함)에 금지 이름과 간접 표현이 없다.
 * 이름 목록은 {@link SiteNames}가 저장소 밖에서 읽고, 없으면(포크 PR 등) 이름 검사만 건너뛰고 간접 표현은 늘 검사한다. 실패해도 일치한 파일·줄 번호와
 * 목록 번호만 보이고 이름은 출력하지 않는다(CI 로그가 공개라서).
 *
 * <p>예외: 이미 적용된 Flyway 마이그레이션(바꾸면 체크섬이 달라진다), 목록을 다루는 {@link SiteNames}와 이 검사 자체.
 */
class BlockedTermsGuardTest {
  private static final List<String> EXCLUDED =
      List.of(
          "src/main/resources/db/migration/",
          "src/test/java/com/loakraken/SiteNames.java",
          "src/test/java/com/loakraken/BlockedTermsGuardTest.java");

  /**
   * 이름 목록에 있어야 하는 이름 수(숫자만 공개). 로컬 목록에 이름을 더하면 이 수와 CI 비밀값 {@code BLOCKED_TERMS}도 같이 고친다. CI 비밀값이
   * 로컬보다 오래돼 테스트 주석의 이름을 놓친 일(#576) 뒤로, 목록이 이보다 적으면 실패한다.
   */
  static final int EXPECTED_NAMES = 34;

  @Test
  void 이름_목록은_비거나_모자라지_않다() {
    // 이름은 출력하지 않고 개수만 보인다(CI 로그가 공개라서).
    System.out.println(
        "금지 이름 " + SiteNames.BLOCKED.size() + "개를 읽었습니다(기대 " + EXPECTED_NAMES + "개)");
    if (SiteNames.BLOCKED.isEmpty()) {
      // 저장소 안 PR·push에서는 CI가 BLOCKED_TERMS_REQUIRED=true를 준다. 포크 PR(비밀값 없음)과 목록 파일이 없는 로컬만 건너뛴다.
      assertThat(System.getenv("BLOCKED_TERMS_REQUIRED"))
          .as("이 실행에는 금지 이름 목록(BLOCKED_TERMS)이 있어야 합니다")
          .isNotEqualTo("true");
      return;
    }
    assertThat(SiteNames.BLOCKED.size())
        .as("금지 이름 목록이 기대보다 적습니다 — CI 비밀값이나 로컬 목록이 오래됐는지 확인")
        .isGreaterThanOrEqualTo(EXPECTED_NAMES);
  }

  @Test
  void 추적하는_모든_파일에_금지_이름과_간접_표현이_없다() throws Exception {
    var hits = scan(patterns());
    assertThat(hits).as("금지 표현이 있는 위치(이름은 출력하지 않음)").isEmpty();
  }

  @Test
  void 검사는_JSON_출처_값도_본다() {
    // 예전에는 source·…Source 값을 건너뛰어 출처 문구에 남은 표현을 놓쳤다(#535).
    var json = "{\"source\": \"" + SiteNames.INDIRECT.get(0) + " 대조\"}";
    assertThat(matches(json, patterns())).isNotEmpty();
    assertThat(matches("{\"source\": \"비교 기준 A 대조\"}", patterns())).isEmpty();
  }

  /** 간접 표현은 늘, 이름은 목록이 있을 때만. 번호는 실패 메시지용. */
  private static List<Pattern> patterns() {
    var patterns = new ArrayList<Pattern>();
    for (var phrase : SiteNames.INDIRECT) patterns.add(Pattern.compile(Pattern.quote(phrase)));
    for (var name : SiteNames.BLOCKED) patterns.add(SiteNames.pattern(name));
    return patterns;
  }

  private static List<String> scan(List<Pattern> patterns) throws Exception {
    var hits = new ArrayList<String>();
    for (var file : trackedFiles()) {
      if (EXCLUDED.stream().anyMatch(file::startsWith)
          || !Files.isRegularFile(Path.of(file))
          || binary(file)) continue;
      var lines = Files.readString(Path.of(file), StandardCharsets.UTF_8).split("\\R", -1);
      for (int i = 0; i < lines.length; i++)
        for (var index : matches(lines[i], patterns)) hits.add(file + ":" + (i + 1) + " #" + index);
    }
    return hits;
  }

  private static List<Integer> matches(String line, List<Pattern> patterns) {
    var found = new ArrayList<Integer>();
    for (int i = 0; i < patterns.size(); i++)
      if (patterns.get(i).matcher(line).find()) found.add(i);
    return found;
  }

  private static boolean binary(String file) {
    return file.matches("(?i).*\\.(png|jpe?g|gif|ico|jar|woff2?|ttf|pdf|zip)$");
  }

  private static List<String> trackedFiles() throws IOException, InterruptedException {
    // 한글 경로가 \354… 같은 따옴표 꼴로 나오지 않게 한다.
    var process =
        new ProcessBuilder("git", "-c", "core.quotepath=off", "ls-files")
            .redirectErrorStream(true)
            .start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as("git ls-files").isZero();
    return output.lines().filter(line -> !line.isBlank()).toList();
  }
}
