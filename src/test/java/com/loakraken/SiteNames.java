package com.loakraken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 사용자에게 보이는 문구·OpenAPI 설명에 쓰면 안 되는 표현(사용자 지시 2026-10-10 23:25, #261).
 *
 * <p>다른 사이트 이름 목록은 공개 저장소에 두지 않는다(해시·부호화 포함). CI는 Actions 비밀값 {@code BLOCKED_TERMS}(줄바꿈 구분)를, 로컬은
 * 저장소에서 무시하는 {@code config/blocked-terms.local}을 읽는다. 둘 다 없으면 이름 검사는 건너뛰고 간접 표현만 검사한다. 목록 내용은 출력하지
 * 않는다.
 */
public final class SiteNames {
  static final Path LOCAL_FILE = Path.of("config", "blocked-terms.local");

  /** 화면에 '목표 사이트' 같은 간접 표현도 쓰지 않는다(성장 도구 10-10 23:47). */
  static final List<String> INDIRECT = List.of("목표 사이트", "두 사이트", "계산 사이트");

  /** 비밀값·로컬 파일에서 읽은 이름. 없으면 빈 목록. */
  public static final List<String> BLOCKED = load();

  private SiteNames() {}

  /** text에 금지 표현이 없는지 본다. 실패 메시지에는 간접 표현만 그대로 쓰고, 이름은 목록 번호만 쓴다(CI 로그·산출물이 공개라서). */
  public static void assertAbsent(String text) {
    var found = new ArrayList<String>();
    for (var phrase : INDIRECT) if (text.contains(phrase)) found.add(phrase);
    for (int i = 0; i < BLOCKED.size(); i++)
      if (pattern(BLOCKED.get(i)).matcher(text).find()) found.add("금지 이름 #" + i);
    if (!found.isEmpty()) throw new AssertionError("응답·문서에 금지 표현이 있습니다: " + found);
  }

  /** 영문 이름은 단어 경계(앞뒤가 영문·숫자가 아님)로, 한글 이름은 부분 문자열로 찾는다. 대소문자 무시. */
  static Pattern pattern(String name) {
    if (name.chars().allMatch(c -> c < 128))
      return Pattern.compile(
          "(?<![A-Za-z0-9])" + Pattern.quote(name) + "(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    return Pattern.compile(Pattern.quote(name));
  }

  private static List<String> load() {
    String text = System.getenv("BLOCKED_TERMS");
    if (text == null || text.isBlank()) {
      if (!Files.isRegularFile(LOCAL_FILE)) return List.of();
      try {
        text = Files.readString(LOCAL_FILE);
      } catch (IOException exception) {
        return List.of();
      }
    }
    var names = new ArrayList<String>();
    for (var line : text.split("\\R")) {
      // 줄 앞의 분류 표시(예: "site: ", "character: ")는 떼고 이름만 쓴다.
      var name = line.strip().replaceFirst("^[a-z]+:\\s+", "");
      if (!name.isEmpty() && !name.startsWith("#")) names.add(name);
    }
    return List.copyOf(names);
  }
}
