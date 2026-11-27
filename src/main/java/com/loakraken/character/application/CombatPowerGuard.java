package com.loakraken.character.application;

import com.loakraken.character.domain.CombatPowerReasons;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 2부 대비 막기(계약 2026-11-28): 전투력·스펙업 모델이 숫자를 내는 화면마다 '이 캐릭터는 계산하지 않음'을 정한다.
 *
 * <ul>
 *   <li>막기 스위치({@code combat-power.raw-shape-guard.enforce})가 켜져 있고, 조회 때 남긴 형태 판정에 HARD 규칙이 하나라도
 *       있으면 막는다. 판정이 없는 옛 캐시(null)는 막지 않는다.
 *   <li>화면별 끄기 스위치({@code combat-power.kill.<화면>})가 켜져 있으면 원문과 상관없이 막는다.
 * </ul>
 *
 * 스위치는 요청마다 읽는다(조회·캐시 때가 아님) — 켜면 다음 요청부터 캐시 만료를 기다리지 않고 적용된다. 막으면 이유 코드는 늘 {@link
 * CombatPowerReasons#GAME_UPDATE_UNSUPPORTED}이다. 지표 {@code
 * loakraken.raw_shape_guard.blocked{surface, cause}}(값 고정, 이름 없음).
 */
@Component
public class CombatPowerGuard {
  public static final String ENFORCE = "combat-power.raw-shape-guard.enforce";

  /** 끄기 스위치 단위. key는 설정 이름 끝(combat-power.kill.key)이자 지표 태그 값. */
  public enum Surface {
    SIMULATOR("simulator"),
    CARD("card"),
    SPEC_UP("spec-up"),
    GEM_RANKING("gem-ranking"),
    CRIT_SPEED("crit-speed"),
    RECEIPT("receipt");

    private final String key;

    Surface(String key) {
      this.key = key;
    }

    public String key() {
      return key;
    }

    public String property() {
      return "combat-power.kill." + key;
    }
  }

  private final Environment environment;
  private final MeterRegistry registry;

  public CombatPowerGuard(Environment environment, MeterRegistry registry) {
    this.environment = environment;
    this.registry = registry;
  }

  public boolean enforced() {
    return environment.getProperty(ENFORCE, Boolean.class, false);
  }

  public boolean killed(Surface surface) {
    return environment.getProperty(surface.property(), Boolean.class, false);
  }

  /** 막기만 켜져 있을 때 이 판정이 막히는가(끄기 스위치와 무관). DB 행처럼 화면 밖에서 판정만 볼 때 쓴다. */
  public static boolean failed(List<String> shapeVerdict) {
    return shapeVerdict != null && !shapeVerdict.isEmpty();
  }

  /** 이 화면에서 이 판정의 캐릭터를 막는가(세지 않음). 한 요청 안에서 같은 판단을 다시 볼 때 쓴다. */
  public boolean isBlocked(CharacterResult result, Surface surface) {
    return cause(result == null ? null : result.shapeVerdict(), surface) != null;
  }

  private String cause(List<String> shapeVerdict, Surface surface) {
    if (killed(surface)) return "kill";
    if (failed(shapeVerdict) && enforced()) return "shape";
    return null;
  }

  /** 이 화면에서 이 판정의 캐릭터를 막는가. 막으면 지표를 하나 센다(요청마다 한 번 부른다). */
  public boolean blocks(List<String> shapeVerdict, Surface surface) {
    var cause = cause(shapeVerdict, surface);
    if (cause == null) return false;
    registry
        .counter("loakraken.raw_shape_guard.blocked", "surface", surface.key(), "cause", cause)
        .increment();
    return true;
  }

  public boolean blocks(CharacterResult result, Surface surface) {
    return blocks(result == null ? null : result.shapeVerdict(), surface);
  }

  /** 막을 때의 고정 문장(화면의 basis·reason·stopReason). */
  public static String sentence() {
    return CombatPowerReasons.GAME_UPDATE_UNSUPPORTED.text();
  }

  public static String code() {
    return CombatPowerReasons.GAME_UPDATE_UNSUPPORTED.code();
  }
}
