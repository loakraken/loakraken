package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterStopList;
import com.loakraken.character.domain.CharacterProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 로컬 대조 전용(#540): 캐릭터 조회 때 받은 공식 armories 응답 본문을 {@code ARMORY_RAW_DUMP_DIR}에 파일로 남긴다. {@code local} 프로필에서
 * 설정이 있을 때만 부품이 만들어진다(운영·스테이징에는 없음). 처리정지 목록 캐릭터(확인 실패 포함)는 쓰지 않는다(B5). 본문만 쓰고 API 키·요청 헤더는 다루지 않는다. 저장 실패는 조회를 막지 않으며, 로그에는
 * 캐릭터 이름을 남기지 않는다.
 */
@Component
@Profile("local")
@ConditionalOnExpression("!'${ARMORY_RAW_DUMP_DIR:}'.isBlank()")
class ArmoryRawDump {
  private static final Logger log = LoggerFactory.getLogger(ArmoryRawDump.class);
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'");
  private final Path dir;
  private final Clock clock;
  private final CharacterStopList stopList;

  ArmoryRawDump(@Value("${ARMORY_RAW_DUMP_DIR}") String dir, Clock clock, CharacterStopList stopList) {
    this.dir = Path.of(dir);
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

  /** 본문을 '이름_UTC시각.json'으로 쓴다. 이름은 문자·숫자만 남긴다. */
  void write(String characterName, String body) {
    if (body == null) return;
    var safe = characterName == null ? "unknown" : characterName.replaceAll("[^\\p{L}\\p{N}]", "");
    if (safe.isEmpty()) safe = "unknown";
    var stamp = STAMP.format(clock.instant().atOffset(java.time.ZoneOffset.UTC));
    try {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(safe + "_" + stamp + ".json"), body, StandardCharsets.UTF_8);
      log.info("event=armory_raw_dumped bytes={}", body.length());
    } catch (IOException | RuntimeException exception) {
      log.warn("event=armory_raw_dump_failed type={}", exception.getClass().getSimpleName());
    }
  }
}
