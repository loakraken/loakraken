package com.loakraken.character.application;

import com.loakraken.character.domain.DealerCombatPower;
import com.loakraken.character.domain.KrakenScoreEngine;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 방어구 재련·상급 재련·완갑 전투력 문(PM 총괄 11-21 (b) 3단계). 방어구 단계는 공식 전투력에 비율을 곱하는 모델이라, 캐릭터의 공식 전투력이 장비로 지은
 * 전투력에서 벗어난 만큼(r = 공식 ÷ 장비로 지은 전투력 − 1) 오차가 커진다(방어구 단계 오차 ≈ 2r). |r| ≤ 0.10%인 딜러만 연다(계산 검증 11-21:
 * 표본 36명 중 25명, 0.038%와 0.123% 사이가 빈 구간이라 그 가운데). 스냅샷마다 한 값이고, 시뮬레이터·스펙업 목록·추천 순서가 모두 이 값을 쓴다.
 *
 * <p>스위치 spec-up.armor-cp-gate.enabled(기본 꺼짐)가 꺼져 있으면 지금처럼 모두 닫혀 있다(DISABLED). r은 화면에 보이지 않고 지표
 * loakraken.combat_power.residual(|r| %)로만 남긴다.
 */
@Component
public class ArmorCpGate {
  /** |r| 상한(0.10%). */
  static final BigDecimal THRESHOLD = new BigDecimal("0.0010");

  public enum State {
    /** 스위치 꺼짐: 지금처럼 닫힘. */
    DISABLED,
    /** |r| ≤ 0.10%: 방어구 전투력을 계산한다. */
    OPEN,
    /** |r| > 0.10%: 닫힘(이유 ARMOR_CP_RESIDUAL_HIGH). */
    RESIDUAL_HIGH,
    /** 장비로 지은 전투력을 낼 수 없음(서포터·입력 없음·공식 전투력 없음): 지금처럼 닫힘. */
    UNAVAILABLE
  }

  private final boolean enabled;
  private final KrakenScoreEngine.Tables engine;
  private final DealerCombatPower.Tables tables;
  private final DistributionSummary openResidual;
  private final DistributionSummary highResidual;

  /** 스냅샷(캐릭터 + 수집 시각) → 상태. 경로 하나가 시뮬레이터를 수백 번 부르므로 한 번만 잰다. */
  private final Map<String, State> recent =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, State> eldest) {
          return size() > 256;
        }
      };

  /** 지금 재는 스냅샷 → 그 결과. */
  private final ConcurrentHashMap<String, CompletableFuture<State>> inFlight =
      new ConcurrentHashMap<>();

  public ArmorCpGate(
      @Value("${spec-up.armor-cp-gate.enabled:false}") boolean enabled,
      KrakenScoreEngine.Tables engine,
      DealerCombatPower.Tables tables,
      MeterRegistry registry) {
    this.enabled = enabled;
    this.engine = engine;
    this.tables = tables;
    // 스위치가 꺼져 있으면 재지 않으므로 지표도 만들지 않는다(코드 리뷰 #1036 P3).
    this.openResidual = enabled ? residual(registry, "open") : null;
    this.highResidual = enabled ? residual(registry, "residual_high") : null;
  }

  private static DistributionSummary residual(MeterRegistry registry, String outcome) {
    return DistributionSummary.builder("loakraken.combat_power.residual")
        .description("공식 전투력과 장비로 지은 딜러 전투력의 차이 |r|(%), 방어구 전투력 문을 잴 때마다")
        .baseUnit("percent")
        .tag("outcome", outcome)
        .register(registry);
  }

  public boolean enabled() {
    return enabled;
  }

  public State state(CharacterResult snapshot) {
    if (!enabled) return State.DISABLED;
    if (snapshot == null || snapshot.details() == null) return State.UNAVAILABLE;
    var key =
        snapshot.details().profile().characterName()
            + "|"
            + (snapshot.fetchedAt() == null ? "-" : snapshot.fetchedAt().toEpochMilli());
    var known = remembered(key);
    if (known != null) return known;
    // 같은 스냅샷은 한 번만 잰다: 재는 중이면 그 결과를 기다리고, 다른 스냅샷은 서로 기다리지 않는다(코드 리뷰 #1036 P3).
    var mine = new CompletableFuture<State>();
    var running = inFlight.putIfAbsent(key, mine);
    if (running != null) return running.join();
    try {
      // 앞선 측정이 기억에 넣고 막 빠졌을 수 있다.
      var state = remembered(key);
      if (state == null) {
        state = measure(snapshot);
        synchronized (recent) {
          recent.put(key, state);
        }
      }
      mine.complete(state);
      return state;
    } catch (RuntimeException e) {
      mine.completeExceptionally(e);
      throw e;
    } finally {
      inFlight.remove(key, mine);
    }
  }

  private State remembered(String key) {
    synchronized (recent) {
      return recent.get(key);
    }
  }

  private State measure(CharacterResult snapshot) {
    var official = snapshot.details().profile().combatPower();
    var input = snapshot.krakenScoreInput();
    if (official == null || official.signum() <= 0 || input == null || !input.isObject())
      return State.UNAVAILABLE;
    var built = DealerCombatPower.of(input, engine, tables);
    if (built == null || built.signum() <= 0) return State.UNAVAILABLE;
    var r = official.divide(built, MathContext.DECIMAL64).subtract(BigDecimal.ONE).abs();
    boolean open = r.compareTo(THRESHOLD) <= 0;
    (open ? openResidual : highResidual).record(r.movePointRight(2).doubleValue());
    return open ? State.OPEN : State.RESIDUAL_HIGH;
  }
}
