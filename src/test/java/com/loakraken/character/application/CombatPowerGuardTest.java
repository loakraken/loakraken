package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CombatPowerReasons;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 2부 대비 막기(계약 2026-11-28). 막기 스위치는 HARD 판정이 있는 캐릭터만, 화면 끄기 스위치는 그 화면의 모든 캐릭터를 막는다. 판정 전(null)·빈 판정은
 * 막기만 켜서는 막지 않는다. 스위치는 부를 때마다 읽는다.
 */
class CombatPowerGuardTest {
  private final MockEnvironment environment = new MockEnvironment();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final CombatPowerGuard guard = new CombatPowerGuard(environment, registry);
  private static final List<String> FAILED = List.of("H6_GEM");

  @Test
  void 스위치가_모두_꺼져_있으면_어떤_판정도_막지_않는다() {
    for (var surface : CombatPowerGuard.Surface.values()) {
      assertThat(guard.blocks(FAILED, surface)).as(surface.name()).isFalse();
      assertThat(guard.blocks(List.of(), surface)).isFalse();
      assertThat(guard.blocks((List<String>) null, surface)).isFalse();
    }
    assertThat(registry.find("loakraken.raw_shape_guard.blocked").counters()).isEmpty();
  }

  @Test
  void 막기를_켜면_HARD_판정만_막고_판정_전_캐시와_빈_판정은_그대로다() {
    environment.setProperty(CombatPowerGuard.ENFORCE, "true");

    for (var surface : CombatPowerGuard.Surface.values()) {
      assertThat(guard.blocks(FAILED, surface)).as(surface.name()).isTrue();
      assertThat(guard.blocks(List.of(), surface)).isFalse();
      assertThat(guard.blocks((List<String>) null, surface)).isFalse();
    }
    assertThat(
            registry
                .counter(
                    "loakraken.raw_shape_guard.blocked", "surface", "spec-up", "cause", "shape")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void 끄기_스위치는_그_화면만_원문과_상관없이_막는다() {
    environment.setProperty("combat-power.kill.gem-ranking", "true");

    assertThat(guard.blocks(List.of(), CombatPowerGuard.Surface.GEM_RANKING)).isTrue();
    assertThat(guard.blocks((List<String>) null, CombatPowerGuard.Surface.GEM_RANKING)).isTrue();
    assertThat(guard.blocks(List.of(), CombatPowerGuard.Surface.SIMULATOR)).isFalse();
    assertThat(guard.blocks(FAILED, CombatPowerGuard.Surface.CARD)).isFalse();
    assertThat(
            registry
                .counter(
                    "loakraken.raw_shape_guard.blocked", "surface", "gem-ranking", "cause", "kill")
                .count())
        .isEqualTo(2.0);
  }

  @Test
  void 스위치는_부를_때마다_읽고_isBlocked는_세지_않는다() {
    var result = new CharacterResult(null, java.time.Instant.EPOCH, null, null, null, FAILED);
    assertThat(guard.isBlocked(result, CombatPowerGuard.Surface.SPEC_UP)).isFalse();

    environment.setProperty(CombatPowerGuard.ENFORCE, "true");

    assertThat(guard.isBlocked(result, CombatPowerGuard.Surface.SPEC_UP)).isTrue();
    assertThat(registry.find("loakraken.raw_shape_guard.blocked").counters()).isEmpty();
  }

  @Test
  void 막을_때의_코드와_문장은_계약과_같다() {
    assertThat(CombatPowerGuard.code()).isEqualTo("GAME_UPDATE_UNSUPPORTED");
    assertThat(CombatPowerGuard.sentence()).isEqualTo("게임 업데이트로 바뀐 장비 정보라 아직 계산하지 않아요");
    assertThat(CombatPowerReasons.classify(CombatPowerGuard.sentence()))
        .isEqualTo(CombatPowerReasons.GAME_UPDATE_UNSUPPORTED);
    assertThat(KrakenScoreService.Reason.GAME_UPDATE_UNSUPPORTED.text())
        .isEqualTo(CombatPowerGuard.sentence());
  }
}
