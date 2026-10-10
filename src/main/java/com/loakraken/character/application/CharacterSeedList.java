package com.loakraken.character.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 시드 이름 대기열(#255). 시작 때 한 번 읽고, 같은 이름은 한 번만 넣는다(멱등). 이름 말고는 아무것도 보관하지 않는다. 로그에도 이름을 남기지 않고 개수만 남긴다.
 */
public class CharacterSeedList {
  private static final Logger log = LoggerFactory.getLogger(CharacterSeedList.class);
  private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}]{1,16}");
  private final ArrayDeque<String> pending = new ArrayDeque<>();
  private final int invalid;

  public CharacterSeedList(CharacterSeedPolicy policy) {
    var names = new ArrayList<String>();
    if (policy.enabled()) {
      names.addAll(policy.names());
      if (!policy.file().isEmpty()) names.addAll(read(Path.of(policy.file())));
    }
    var unique = new LinkedHashSet<String>();
    int rejected = 0;
    for (var raw : names) {
      if (raw == null || raw.isBlank()) continue;
      var name = new SearchCharacterCommand(raw).characterName();
      if (NAME.matcher(name).matches()) unique.add(name);
      else rejected++;
    }
    pending.addAll(unique);
    invalid = rejected;
    if (policy.enabled())
      log.info("통계 시드 목록을 읽었습니다. 이름 {}개, 형식이 맞지 않아 뺀 줄 {}개", unique.size(), rejected);
  }

  static List<String> read(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
          .map(String::strip)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .toList();
    } catch (IOException exception) {
      // 경로가 틀려도 앱은 뜬다. 시드 없이 기존 수집만 한다.
      log.warn("통계 시드 파일을 읽지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
      return List.of();
    }
  }

  public synchronized String peek() {
    return pending.peekFirst();
  }

  /** 처리했거나 건너뛴 이름을 뺀다. 다시 넣지 않는다. */
  public synchronized void done(String name) {
    pending.remove(name);
  }

  public synchronized int pendingCount() {
    return pending.size();
  }

  public int invalidCount() {
    return invalid;
  }
}
