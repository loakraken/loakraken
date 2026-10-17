package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterStopList;
import com.loakraken.character.domain.CharacterProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 로컬 대조 전용(#540): 캐릭터 조회 때 받은 공식 armories 응답 본문을 {@code ARMORY_RAW_DUMP_DIR}에 파일로 남긴다. {@code local}
 * 프로필이고 {@code prod}가 아니며 설정이 있을 때만 부품이 만들어진다. 운영 프로필에 설정이 있으면 {@link ProdGuard}가 시작을 막는다. 저장 경로는
 * 절대 경로이고 git 저장소 밖이어야 한다(아니면 시작 실패, 원문이 커밋되지 않게). 처리정지 목록 캐릭터(확인 실패 포함)는 쓰지 않는다(B5). 본문만 쓰고
 * API 키·요청 헤더는 다루지 않는다. 저장 실패는 조회를 막지 않으며, 로그에는 캐릭터 이름을 남기지 않는다.
 */
@Component
@Profile("local & !prod")
@Conditional(ArmoryRawDump.DirConfigured.class)
class ArmoryRawDump {
  static final String PROPERTY = "ARMORY_RAW_DUMP_DIR";
  private static final Logger log = LoggerFactory.getLogger(ArmoryRawDump.class);
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'");
  private static final int MAX_SUFFIX = 100;
  private final Path dir;
  private final Clock clock;
  private final CharacterStopList stopList;

  @Autowired
  ArmoryRawDump(Environment environment, Clock clock, CharacterStopList stopList) {
    this(outsideRepository(Path.of(environment.getProperty(PROPERTY, "")), null), clock, stopList);
  }

  /** 경로를 이미 확인한 경우(테스트). */
  ArmoryRawDump(Path dir, Clock clock, CharacterStopList stopList) {
    this.dir = dir;
    this.clock = clock;
    this.stopList = stopList;
  }

  /** 처리정지 목록 밖이면 본문을 쓴다(목록 확인에 실패해도 쓰지 않음). */
  void write(CharacterProfile profile, String body) {
    if (profile == null || !stopList.recordable(profile.characterName(), profile.serverName())) {
      log.info("event=armory_raw_dump_skipped reason=not_recordable");
      return;
    }
    write(profile.characterName(), body);
  }

  /**
   * 본문을 '이름_UTC시각.json'으로 쓴다. 이름은 문자·숫자만 남긴다. 같은 이름이 이미 있으면 덮어쓰지 않고 '-1', '-2'를 붙인다(같은 밀리초에 두 번
   * 저장).
   */
  void write(String characterName, String body) {
    if (body == null) return;
    var safe = characterName == null ? "unknown" : characterName.replaceAll("[^\\p{L}\\p{N}]", "");
    if (safe.isEmpty()) safe = "unknown";
    var base = safe + "_" + STAMP.format(clock.instant().atOffset(java.time.ZoneOffset.UTC));
    try {
      Files.createDirectories(dir);
      for (int i = 0; i < MAX_SUFFIX; i++) {
        var file = dir.resolve(base + (i == 0 ? "" : "-" + i) + ".json");
        try {
          Files.writeString(
              file, body, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
          log.info("event=armory_raw_dumped bytes={}", body.length());
          return;
        } catch (FileAlreadyExistsException exists) {
          // 다음 꼬리로
        }
      }
      log.warn("event=armory_raw_dump_failed type=TooManyDuplicates");
    } catch (IOException | RuntimeException exception) {
      log.warn("event=armory_raw_dump_failed type={}", exception.getClass().getSimpleName());
    }
  }

  /**
   * 절대 경로이고, 그 위(링크를 따라간 실제 경로 포함) 어느 폴더에도 .git이 없어야 한다. {@code stopAt}이 있으면 그 폴더까지만 올라간다(테스트,
   * 운영 코드는 null).
   */
  static Path outsideRepository(Path dir, Path stopAt) {
    if (!dir.isAbsolute()) throw new IllegalStateException(PROPERTY + "는 절대 경로여야 합니다.");
    var normalized = dir.normalize();
    insideRepository(normalized, stopAt);
    var existing = normalized;
    while (existing != null && !Files.exists(existing)) existing = existing.getParent();
    if (existing != null) {
      try {
        insideRepository(existing.toRealPath(), stopAt);
      } catch (IOException exception) {
        throw new IllegalStateException(PROPERTY + " 경로를 확인할 수 없습니다.");
      }
    }
    return normalized;
  }

  private static void insideRepository(Path path, Path stopAt) {
    for (var p = path; p != null && (stopAt == null || p.startsWith(stopAt)); p = p.getParent()) {
      if (Files.exists(p.resolve(".git")))
        throw new IllegalStateException(PROPERTY + "는 git 저장소 밖이어야 합니다.");
    }
  }

  /** 설정 값이 비어 있지 않을 때. 값을 식에 넣지 않아 경로에 따옴표가 있어도 시작이 깨지지 않는다. */
  static class DirConfigured implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      return StringUtils.hasText(context.getEnvironment().getProperty(PROPERTY));
    }
  }

  /** 운영 프로필에 저장 경로가 설정되면 시작을 막는다(local과 prod가 함께 켜진 경우 포함). */
  @Component
  @Profile("prod")
  @Conditional(DirConfigured.class)
  static class ProdGuard {
    ProdGuard() {
      throw new IllegalStateException(PROPERTY + "는 운영 프로필에서 쓸 수 없습니다.");
    }
  }
}
