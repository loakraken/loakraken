package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;

/** 로컬 전용 공식 원문 저장(#540). */
class ArmoryRawDumpTest {
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:04:05.678Z"), ZoneOffset.UTC);

  @Test
  void 본문을_이름과_UTC_시각_파일로_그대로_쓴다(@TempDir Path dir) throws Exception {
    new ArmoryRawDump(dir.resolve("raw").toString(), clock).write("테스트캐릭", "{\"ArmoryProfile\":{}}");
    var file = dir.resolve("raw").resolve("테스트캐릭_20261018T030405678Z.json");
    assertThat(Files.readString(file)).isEqualTo("{\"ArmoryProfile\":{}}");
  }

  @Test
  void 이름의_문자와_숫자_밖은_지우고_저장_실패는_조회를_막지_않는다(@TempDir Path dir) throws Exception {
    new ArmoryRawDump(dir.toString(), clock).write("../가나", "{}");
    assertThat(dir.resolve("가나_20261018T030405678Z.json")).exists();
    // 디렉터리 자리에 파일이 있어 쓸 수 없어도 예외가 밖으로 나오지 않는다.
    var blocked = dir.resolve("blocked");
    Files.writeString(blocked, "x");
    new ArmoryRawDump(blocked.toString(), clock).write("다", "{}");
  }

  @Test
  void local_프로필에서_설정이_있을_때만_만들어진다() {
    assertThat(ArmoryRawDump.class.getAnnotation(Profile.class).value()).containsExactly("local");
    // 환경 변수 ARMORY_RAW_DUMP_DIR가 비어 있지 않을 때만(빈 값이면 현재 폴더에 쓰지 않게).
    assertThat(ArmoryRawDump.class.getAnnotation(ConditionalOnExpression.class).value())
        .isEqualTo("!'${ARMORY_RAW_DUMP_DIR:}'.isBlank()");
  }
}
