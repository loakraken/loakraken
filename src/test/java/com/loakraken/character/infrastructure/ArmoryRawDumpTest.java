package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterStopList;
import com.loakraken.character.domain.CharacterProfile;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;

/** 로컬 전용 공식 원문 저장(#540). */
class ArmoryRawDumpTest {
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:04:05.678Z"), ZoneOffset.UTC);
  private final CharacterStopList stopList = mock(CharacterStopList.class);

  private ArmoryRawDump dump(Path dir) {
    return new ArmoryRawDump(dir.toString(), clock, stopList);
  }

  private static CharacterProfile profile(String name) {
    return new CharacterProfile(
        name, "테스트서버", "바드", 70, new BigDecimal("1700"), null, null, null, null, null, List.of());
  }

  @Test
  void 본문을_이름과_UTC_시각_파일로_그대로_쓴다(@TempDir Path dir) throws Exception {
    when(stopList.recordable("테스트캐릭", "테스트서버")).thenReturn(true);
    dump(dir.resolve("raw")).write(profile("테스트캐릭"), "{\"ArmoryProfile\":{}}");
    var file = dir.resolve("raw").resolve("테스트캐릭_20261018T030405678Z.json");
    assertThat(Files.readString(file)).isEqualTo("{\"ArmoryProfile\":{}}");
  }

  @Test
  void 처리정지_목록이거나_확인하지_못한_캐릭터는_쓰지_않는다(@TempDir Path dir) throws Exception {
    // recordable은 목록에 있거나 확인에 실패하면 false다.
    when(stopList.recordable("정지캐릭", "테스트서버")).thenReturn(false);
    dump(dir).write(profile("정지캐릭"), "{}");
    dump(dir).write((CharacterProfile) null, "{}");
    try (var files = Files.list(dir)) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  void 이름의_문자와_숫자_밖은_지우고_저장_실패는_조회를_막지_않는다(@TempDir Path dir) throws Exception {
    dump(dir).write("../가나", "{}");
    assertThat(dir.resolve("가나_20261018T030405678Z.json")).exists();
    // 디렉터리 자리에 파일이 있어 쓸 수 없어도 예외가 밖으로 나오지 않는다.
    var blocked = dir.resolve("blocked");
    Files.writeString(blocked, "x");
    dump(blocked).write("다", "{}");
  }

  @Test
  void local_프로필에서_설정이_있을_때만_만들어진다() {
    assertThat(ArmoryRawDump.class.getAnnotation(Profile.class).value()).containsExactly("local");
    // 환경 변수 ARMORY_RAW_DUMP_DIR가 비어 있지 않을 때만(빈 값이면 현재 폴더에 쓰지 않게).
    assertThat(ArmoryRawDump.class.getAnnotation(ConditionalOnExpression.class).value())
        .isEqualTo("!'${ARMORY_RAW_DUMP_DIR:}'.isBlank()");
  }
}
