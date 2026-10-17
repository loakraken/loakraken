package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;

/** 로컬 전용 공식 원문 저장(#540). */
class ArmoryRawDumpTest {
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:04:05.678Z"), ZoneOffset.UTC);
  private final CharacterStopList stopList = mock(CharacterStopList.class);

  private ArmoryRawDump dump(Path dir) {
    return new ArmoryRawDump(dir, clock, stopList);
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
  void local_프로필만이고_설정이_있을_때만_만들어진다() {
    assertThat(ArmoryRawDump.class.getAnnotation(Profile.class).value())
        .containsExactly("local & !staging & !prod");
    // local과 staging이 함께 켜지면 저장 부품이 없고, 설정이 있으면 시작이 실패한다.
    new ApplicationContextRunner()
        .withPropertyValues("ARMORY_RAW_DUMP_DIR=/raw-dump")
        .withUserConfiguration(ArmoryRawDump.class)
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("local", "staging"))
        .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ArmoryRawDump.class));
    new ApplicationContextRunner()
        .withPropertyValues("ARMORY_RAW_DUMP_DIR=/raw-dump")
        .withUserConfiguration(ArmoryRawDump.ProdGuard.class)
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("local", "staging"))
        .run(context -> assertThat(context).hasFailed());
    // 환경 변수 ARMORY_RAW_DUMP_DIR가 비어 있지 않을 때만(빈 값이면 현재 폴더에 쓰지 않게).
    new ApplicationContextRunner()
        .withPropertyValues("ARMORY_RAW_DUMP_DIR= ")
        .withUserConfiguration(ArmoryRawDump.class)
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
        .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ArmoryRawDump.class));
  }

  @Test
  void 같은_밀리초에_두_번_저장해도_덮어쓰지_않는다(@TempDir Path dir) throws Exception {
    dump(dir).write("가", "{\"n\":1}");
    dump(dir).write("가", "{\"n\":2}");
    assertThat(Files.readString(dir.resolve("가_20261018T030405678Z.json"))).isEqualTo("{\"n\":1}");
    assertThat(Files.readString(dir.resolve("가_20261018T030405678Z-1.json"))).isEqualTo("{\"n\":2}");
  }

  @Test
  void 경로에_따옴표가_있어도_조건_식이_깨지지_않는다() {
    // 값이 식에 들어가지 않으므로, 운영 프로필에서는 식 오류가 아니라 의도한 시작 실패가 난다.
    new ApplicationContextRunner()
        .withPropertyValues("ARMORY_RAW_DUMP_DIR=/raw'dump")
        .withUserConfiguration(ArmoryRawDump.ProdGuard.class)
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("운영 프로필"));
  }

  @Test
  void prod_프로필에_설정이_있으면_local과_함께여도_시작이_실패한다() {
    var guard =
        new ApplicationContextRunner()
            .withPropertyValues("ARMORY_RAW_DUMP_DIR=/raw-dump")
            .withUserConfiguration(ArmoryRawDump.ProdGuard.class);
    guard
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("local", "prod"))
        .run(context -> assertThat(context).hasFailed());
    guard
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
        .run(context -> assertThat(context).hasFailed());
    guard
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
        .run(context -> assertThat(context).hasNotFailed());
    // 설정이 없으면 운영에서도 막지 않는다.
    new ApplicationContextRunner()
        .withUserConfiguration(ArmoryRawDump.ProdGuard.class)
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
        .run(context -> assertThat(context).hasNotFailed());
    // local과 prod가 함께 켜지면 저장 부품 자체가 만들어지지 않는다.
    new ApplicationContextRunner()
        .withPropertyValues("ARMORY_RAW_DUMP_DIR=/raw-dump")
        .withUserConfiguration(ArmoryRawDump.class)
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("local", "prod"))
        .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ArmoryRawDump.class));
  }

  @Test
  void 저장_경로가_상대_경로이거나_git_저장소_안이면_시작이_실패한다(@TempDir Path temp) throws Exception {
    var dir = temp.toRealPath();
    assertThatThrownBy(() -> ArmoryRawDump.outsideRepository(Path.of("raw"), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("절대 경로");
    var repo = dir.resolve("repo");
    Files.createDirectories(repo.resolve(".git"));
    // 아직 없는 하위 폴더도, ..로 돌아 들어가는 경로도 거부한다.
    assertThatThrownBy(() -> ArmoryRawDump.outsideRepository(repo.resolve("tmp").resolve("raw"), dir))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("저장소 밖");
    assertThatThrownBy(
            () ->
                ArmoryRawDump.outsideRepository(
                    dir.resolve("out").resolve("..").resolve("repo").resolve("raw"), dir))
        .isInstanceOf(IllegalStateException.class);
    // git 작업 트리(.git 파일)도 저장소다.
    var worktree = dir.resolve("wt");
    Files.createDirectories(worktree);
    Files.writeString(worktree.resolve(".git"), "gitdir: elsewhere");
    assertThatThrownBy(() -> ArmoryRawDump.outsideRepository(worktree.resolve("raw"), dir))
        .isInstanceOf(IllegalStateException.class);
    assertThat(ArmoryRawDump.outsideRepository(dir.resolve("outside").resolve("raw"), dir))
        .isEqualTo(dir.resolve("outside").resolve("raw"));
  }
}
