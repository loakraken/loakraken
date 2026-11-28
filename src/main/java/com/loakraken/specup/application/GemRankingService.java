package com.loakraken.specup.application;

import com.loakraken.character.application.CombatPowerSimulationService;
import com.loakraken.character.domain.ArkGridGemProcessing;
import com.loakraken.character.domain.ArkGridGemTables;
import com.loakraken.character.domain.CombatPowerReasons;
import com.loakraken.common.api.BusinessException;
import com.loakraken.market.application.ArkGridGemPriceService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 아크 그리드 젬 교체 순위(M49 2단계, 계산 검증 명세 2026-11-22 §2·§3·§4 REVISED, 계약 초안 'gem-ranking'). 딜러의 장착 젬 칸마다
 * 그 코어 계열의 세 종류 × 효과 정책 × 초기화로, 새 젬(분배 직후)을 '합 ≥ T가 되면 완료' 정책으로 가공했을 때 G1 교체 값이 성공(정책 효과 ∧ 의지력 ∧
 * Δ전투력 > 0)일 확률을 미리 계산한 끝 분포(ArkGridGemTables)로 구하고, T 가운데 확률이 가장 큰 것을 고른다. 공식 호출 없음: 캐릭터 캐시와 시세 보드
 * 캐시만 읽는다.
 */
@Service
public class GemRankingService {
  static final Duration RETRY_AFTER = Duration.ofSeconds(2);
  static final String RULE_VERSION = "gem-ranking-v1";

  /** 정책: A·B = 그 종류의 딜러 옵션 두 개(명세 §2). */
  public enum Policy {
    ALL,
    A_OR_B,
    A,
    A_AND_B
  }

  /** 등급(v1: 영웅·희귀, PM 총괄 11-22). 페온은 구매 한 번에 영웅 12 · 희귀 6(공지 13204). */
  public enum Grade {
    HERO("영웅", 12),
    RARE("희귀", 6);

    final String label;
    final int pheon;

    Grade(String label, int pheon) {
      this.label = label;
      this.pheon = pheon;
    }
  }

  /** 종류 → 딜러 옵션 A·B(명세 §2). */
  static final Map<String, List<String>> DEALER_OPTIONS =
      Map.of(
          "안정", List.of("공격력", "추가 피해"),
          "침식", List.of("공격력", "추가 피해"),
          "견고", List.of("공격력", "보스 피해"),
          "왜곡", List.of("공격력", "보스 피해"),
          "불변", List.of("추가 피해", "보스 피해"),
          "붕괴", List.of("추가 피해", "보스 피해"));

  static final List<String> ORDER_TYPES = List.of("안정", "견고", "불변");
  static final List<String> CHAOS_TYPES = List.of("침식", "왜곡", "붕괴");

  public record Query(
      String characterName,
      Grade grade,
      Set<Policy> policies,
      Set<Boolean> resets,
      Long resetTicketGold,
      BigDecimal pheonGold,
      int limit) {}

  public record OptionLevel(String name, BigDecimal level) {}

  public record Cost(long gem, long pheon, long processingGold, long resetTicket, long total) {}

  public record CurrentGem(String type, Integer point, List<OptionLevel> effects) {}

  public record Row(
      int rank,
      int slotIndex,
      int gemIndex,
      String coreName,
      CurrentGem currentGem,
      String gemType,
      String grade,
      String policy,
      boolean reset,
      int targetTotal,
      BigDecimal successProbability,
      BigDecimal expectedGems,
      BigDecimal expectedGainPct,
      List<OptionLevel> expectedOptions,
      BigDecimal expectedWillEfficiency,
      BigDecimal expectedPoint,
      Cost expectedCost,
      BigDecimal goldPerPercent,
      List<String> missingPrices) {}

  public record Result(
      String ruleVersion,
      String characterName,
      Instant fetchedAt,
      String status,
      String reasonCode,
      String basis,
      Instant pricesObservedAt,
      int totalRows,
      List<Row> rows,
      List<String> assumptions) {}

  static final List<String> ASSUMPTIONS =
      List.of(
          "가공 횟수(영웅 9 · 희귀 7)와 1회 900골드: 공개 글과 게임 화면 기준 값입니다.",
          "확률: 공식 확률 정보 '젬 가공, 젬 융합'(2025-08-20) 기준입니다.",
          "정책은 '네 옵션 포인트 합이 목표(targetTotal) 이상이 되면 완료'입니다. 다른 항목 보기·완료·초기화는 그 목표로 가장 좋게 고르고, 효과 이름은 보지 않습니다.",
          "성공은 정책의 효과 조건, 의지력, 이 칸에 넣었을 때 전투력 증가를 모두 만족한 젬입니다(지금 젬과 바꾼 값).",
          "비용은 추정이고, 페온 1개 골드와 초기화권 가격은 넣은 값입니다. 젬 가격은 거래소 시세 캐시(1개 즉시 구매 최저가)입니다.");

  private final CombatPowerSimulationService simulator;
  private final ArkGridGemPriceService prices;
  private final ArkGridGemTables tables = ArkGridGemTables.load();
  private final ArkGridGemProcessing processing =
      new ArkGridGemProcessing(ArkGridGemProcessing.rules());
  private final Semaphore seats;
  private final io.micrometer.core.instrument.Counter cacheHits;
  private final io.micrometer.core.instrument.Counter cacheMisses;

  /** 결과 캐시 크기(최근에 쓴 순서). 캐릭터 캐시 수집 시각·시세 관측 시각이 열쇠에 있어 둘 중 하나가 바뀌면 자연히 새 열쇠다. */
  static final int CACHE_ENTRIES = 256;

  /** 요청 limit 상한(요청 검사 1~200과 같다). 캐시에는 이만큼만 둔다(#1097 코드 리뷰 P3: 캐릭터 하나 줄이 수백 개라 메모리 상한). */
  static final int MAX_ROWS = 200;

  /** 순위를 매긴 위쪽 줄(최대 {@link #MAX_ROWS}개)과 전체 줄 수. limit은 꺼낼 때 자른다. */
  private record Ranked(Instant pricesObservedAt, List<Row> rows, int total) {}

  /** 결과 캐시 열쇠(#1095 코드 리뷰 P2): 같은 캐릭터 캐시·등급·정책·초기화·사용자 가격·젬 시세면 같은 줄이다. */
  private record CacheKey(
      String characterName,
      Instant fetchedAt,
      Grade grade,
      Set<Policy> policies,
      Set<Boolean> resets,
      Long resetTicketGold,
      BigDecimal pheonGold,
      Instant pricesObservedAt,
      Map<String, Long> gemPrices) {}

  private final Map<CacheKey, Ranked> cache =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, Ranked> eldest) {
          return size() > CACHE_ENTRIES;
        }
      };

  public GemRankingService(
      CombatPowerSimulationService simulator,
      ArkGridGemPriceService prices,
      @Value("${combat-power-sim.gem-ranking.max-concurrent:2}") int maxConcurrent,
      io.micrometer.core.instrument.MeterRegistry registry) {
    this.simulator = simulator;
    this.prices = prices;
    this.seats = new Semaphore(Math.max(1, maxConcurrent));
    this.cacheHits =
        io.micrometer.core.instrument.Counter.builder("loakraken.gem_ranking.cache")
            .tag("outcome", "hit")
            .description("젬 교체 순위를 결과 캐시에서 준 수")
            .register(registry);
    this.cacheMisses =
        io.micrometer.core.instrument.Counter.builder("loakraken.gem_ranking.cache")
            .tag("outcome", "miss")
            .description("젬 교체 순위를 새로 계산한 수")
            .register(registry);
  }

  /**
   * 캐시에 있으면 계산 자리 없이 바로 준다. 없으면 자리를 받아 계산하고(자리가 없으면 429 GEM_RANKING_BUSY) 넣는다. 캐릭터·시세 읽기는 캐시만이라
   * 가볍다.
   */
  private com.loakraken.character.application.CombatPowerGuard guard;

  @org.springframework.beans.factory.annotation.Autowired
  void guard(com.loakraken.character.application.CombatPowerGuard guard) {
    this.guard = guard;
  }

  public Result rank(Query query) {
    var result = simulator.snapshot(query.characterName());
    var name = result.details().profile().characterName();
    var fetchedAt = result.fetchedAt();
    // 2부 대비 막기(계약 2026-11-28): 결과 캐시보다 먼저 본다 — 스위치를 켜면 다음 요청부터 rows=[].
    var role =
        guard != null
                && guard.blocks(
                    result,
                    com.loakraken.character.application.CombatPowerGuard.Surface.GEM_RANKING)
            ? com.loakraken.character.application.CombatPowerGuard.sentence()
            : CombatPowerSimulationService.roleReason(result);
    if (role != null)
      return new Result(
          RULE_VERSION,
          name,
          fetchedAt,
          "UNSUPPORTED",
          CombatPowerReasons.classify(role).code(),
          role,
          null,
          0,
          List.of(),
          ASSUMPTIONS);
    Instant pricesAt = null;
    var gemPrice = new LinkedHashMap<String, Long>();
    for (var price : prices.cachedPrices())
      if (query.grade().label.equals(price.grade())) {
        gemPrice.put(price.type(), price.unitPrice());
        if (pricesAt == null || price.observedAt().isBefore(pricesAt))
          pricesAt = price.observedAt();
      }
    var key =
        new CacheKey(
            name,
            fetchedAt,
            query.grade(),
            Set.copyOf(query.policies()),
            Set.copyOf(query.resets()),
            query.resetTicketGold(),
            query.pheonGold() == null ? null : query.pheonGold().stripTrailingZeros(),
            pricesAt,
            Map.copyOf(gemPrice));
    Ranked ranked;
    synchronized (cache) {
      ranked = cache.get(key);
    }
    if (ranked != null) cacheHits.increment();
    else {
      if (!seats.tryAcquire())
        throw new BusinessException(SpecUpErrorCode.GEM_RANKING_BUSY, RETRY_AFTER);
      try {
        cacheMisses.increment();
        var all = compute(query, result, gemPrice);
        ranked =
            new Ranked(
                pricesAt, List.copyOf(all.subList(0, Math.min(MAX_ROWS, all.size()))), all.size());
      } finally {
        seats.release();
      }
      synchronized (cache) {
        cache.put(key, ranked);
      }
    }
    var limited = new ArrayList<Row>();
    for (int i = 0; i < ranked.rows().size() && i < query.limit(); i++)
      limited.add(ranked.rows().get(i));
    return new Result(
        RULE_VERSION,
        name,
        fetchedAt,
        "OK",
        null,
        null,
        ranked.pricesObservedAt(),
        ranked.total(),
        List.copyOf(limited),
        ASSUMPTIONS);
  }

  /** 딜러 캐릭터의 모든 줄을 순위대로(rank 채움). */
  private List<Row> compute(
      Query query,
      com.loakraken.character.application.CharacterResult result,
      Map<String, Long> gemPrice) {
    var grid = result.details().loadout().arkGrid();
    var rows = new ArrayList<Row>();
    if (grid != null && grid.slots() != null)
      for (var slot : grid.slots()) {
        if (slot == null || slot.index() == null || slot.gems() == null || slot.name() == null)
          continue;
        var series = slot.name().strip();
        var types =
            series.startsWith("질서") ? ORDER_TYPES : series.startsWith("혼돈") ? CHAOS_TYPES : null;
        if (types == null) continue;
        for (var gem : slot.gems()) {
          if (gem == null || gem.index() == null) continue;
          for (var type : types) {
            var evaluator = simulator.gemSwapEvaluator(result, slot.index(), gem.index(), type);
            if (evaluator == null || evaluator.reason() != null) continue;
            rows.addAll(
                candidateRows(
                    query,
                    slot.index(),
                    gem.index(),
                    slot.name(),
                    current(gem),
                    type,
                    evaluator,
                    gemPrice.get(type)));
          }
        }
      }
    rows.sort(ORDER);
    var ranked = new ArrayList<Row>();
    for (int i = 0; i < rows.size(); i++) ranked.add(withRank(rows.get(i), i + 1));
    return List.copyOf(ranked);
  }

  private static CurrentGem current(com.loakraken.character.domain.CharacterLoadout.GridGem gem) {
    var detail = gem.detail();
    if (detail == null) return new CurrentGem(null, null, List.of());
    var effects = new ArrayList<OptionLevel>();
    if (detail.options() != null)
      for (var option : detail.options())
        if (option != null && option.name() != null && option.level() != null)
          effects.add(new OptionLevel(option.name().strip(), BigDecimal.valueOf(option.level())));
    // 종류는 공식 이름 '혼돈의 젬 : 침식'의 뒤쪽(GemDetail.gemType은 계열 '혼돈'이다).
    String type = detail.name();
    if (type != null && type.contains(":")) type = type.substring(type.indexOf(':') + 1).strip();
    return new CurrentGem(type, detail.corePoint(), List.copyOf(effects));
  }

  /** 한 칸 × 종류의 정책 × 초기화 줄(성공 확률 > 0만). */
  private List<Row> candidateRows(
      Query query,
      int slotIndex,
      int gemIndex,
      String coreName,
      CurrentGem currentGem,
      String type,
      CombatPowerSimulationService.GemSwapEvaluator evaluator,
      Long gemUnitPrice) {
    var options = evaluator.options();
    var dealer = DEALER_OPTIONS.get(type);
    // 끝 상태(순서 있는 네 레벨 × 순서 있는 이름 쌍)마다 G1 성공 여부와 Δ%를 한 번 구한다.
    int states = 625 * 12;
    var gain = new double[states];
    var ok = new boolean[states];
    var stateTuple = new int[states];
    var stateNames = new int[states][2];
    var stateLevels = new int[states][4];
    int s = 0;
    for (int w = 1; w <= 5; w++)
      for (int p = 1; p <= 5; p++)
        for (int e1 = 1; e1 <= 5; e1++)
          for (int e2 = 1; e2 <= 5; e2++) {
            int tuple = processing.index(new int[] {w, p, e1, e2});
            for (int a = 0; a < 4; a++)
              for (int b = 0; b < 4; b++) {
                if (a == b) continue;
                double ratio = evaluator.ratio(p, options.get(a), e1, options.get(b), e2);
                ok[s] = evaluator.willFits(w) && !Double.isNaN(ratio) && ratio > 1;
                gain[s] = Double.isNaN(ratio) ? 0 : (ratio - 1) * 100;
                stateTuple[s] = tuple;
                stateNames[s][0] = a;
                stateNames[s][1] = b;
                stateLevels[s] = new int[] {w, p, e1, e2};
                s++;
              }
          }
    var perms = new double[processing.tupleCount()];
    for (int t = 0; t < perms.length; t++) perms[t] = processing.permutations(t);
    var rows = new ArrayList<Row>();
    for (var policy : query.policies())
      for (boolean reset : query.resets()) {
        if (reset && query.resetTicketGold() == null) continue;
        Best best = null;
        for (var table : tables.all(query.grade().name(), reset)) {
          double p = 0;
          double gainSum = 0;
          double willSum = 0;
          double pointSum = 0;
          var optionSum = new double[4];
          var terminal = table.terminal();
          for (int i = 0; i < states; i++) {
            if (!ok[i]
                || !policyMatches(
                    policy, dealer, options.get(stateNames[i][0]), options.get(stateNames[i][1])))
              continue;
            double weight = terminal[stateTuple[i]] / perms[stateTuple[i]] / 12;
            if (weight == 0) continue;
            p += weight;
            gainSum += weight * gain[i];
            willSum += weight * stateLevels[i][0];
            pointSum += weight * stateLevels[i][1];
            optionSum[stateNames[i][0]] += weight * stateLevels[i][2];
            optionSum[stateNames[i][1]] += weight * stateLevels[i][3];
          }
          if (p <= 0) continue;
          if (best == null || p > best.p + 1e-15)
            best = new Best(table, p, gainSum, willSum, pointSum, optionSum);
        }
        if (best == null) continue;
        rows.add(
            row(
                query,
                slotIndex,
                gemIndex,
                coreName,
                currentGem,
                type,
                options,
                policy,
                reset,
                best,
                gemUnitPrice));
      }
    return rows;
  }

  private record Best(
      ArkGridGemProcessing.Table table,
      double p,
      double gainSum,
      double willSum,
      double pointSum,
      double[] optionSum) {}

  static boolean policyMatches(Policy policy, List<String> dealer, String first, String second) {
    boolean hasA = dealer.get(0).equals(first) || dealer.get(0).equals(second);
    boolean hasB = dealer.get(1).equals(first) || dealer.get(1).equals(second);
    return switch (policy) {
      case ALL -> true;
      case A_OR_B -> hasA || hasB;
      case A -> hasA;
      case A_AND_B -> hasA && hasB;
    };
  }

  private Row row(
      Query query,
      int slotIndex,
      int gemIndex,
      String coreName,
      CurrentGem currentGem,
      String type,
      List<String> options,
      Policy policy,
      boolean reset,
      Best best,
      Long gemUnitPrice) {
    double p = best.p();
    double gems = 1 / p;
    var expectedOptions = new ArrayList<OptionLevel>();
    for (int i = 0; i < options.size(); i++)
      if (best.optionSum()[i] > 0)
        expectedOptions.add(new OptionLevel(options.get(i), scale(best.optionSum()[i] / p, 2)));
    var missing = new ArrayList<String>();
    if (gemUnitPrice == null) missing.add("GEM");
    if (query.pheonGold() == null) missing.add("PHEON");
    if (reset && query.resetTicketGold() == null) missing.add("RESET_TICKET");
    double gainPct = best.gainSum() / p;
    Cost cost = null;
    BigDecimal perPercent = null;
    if (missing.isEmpty()) {
      long gem = Math.round(gemUnitPrice * gems);
      long pheon = Math.round(query.grade().pheon * query.pheonGold().doubleValue() * gems);
      long processingGold = Math.round(best.table().expectedGold() * gems);
      long tickets =
          reset ? Math.round(best.table().expectedTickets() * gems * query.resetTicketGold()) : 0;
      long total = gem + pheon + processingGold + tickets;
      cost = new Cost(gem, pheon, processingGold, tickets, total);
      if (gainPct > 0) perPercent = scale(total / gainPct, 0);
    }
    return new Row(
        0,
        slotIndex,
        gemIndex,
        coreName,
        currentGem,
        type,
        query.grade().name(),
        policy.name(),
        reset,
        best.table().target(),
        scale(p, 6),
        scale(gems, 2),
        scale(gainPct, 3),
        List.copyOf(expectedOptions),
        scale(best.willSum() / p, 2),
        scale(best.pointSum() / p, 2),
        cost,
        perPercent,
        List.copyOf(missing));
  }

  /** 순위: 비용이 있는 줄은 1% 골드 오름차순(같으면 확률 높은 쪽), 그 뒤 비용 없는 줄은 Δ% × 확률 내림차순. */
  static final Comparator<Row> ORDER =
      Comparator.<Row, Boolean>comparing(row -> row.goldPerPercent() == null)
          .thenComparing(
              (a, b) -> {
                if (a.goldPerPercent() != null && b.goldPerPercent() != null) {
                  int c = a.goldPerPercent().compareTo(b.goldPerPercent());
                  return c != 0 ? c : b.successProbability().compareTo(a.successProbability());
                }
                var va = a.expectedGainPct().multiply(a.successProbability());
                var vb = b.expectedGainPct().multiply(b.successProbability());
                return vb.compareTo(va);
              });

  private static Row withRank(Row row, int rank) {
    return new Row(
        rank,
        row.slotIndex(),
        row.gemIndex(),
        row.coreName(),
        row.currentGem(),
        row.gemType(),
        row.grade(),
        row.policy(),
        row.reset(),
        row.targetTotal(),
        row.successProbability(),
        row.expectedGems(),
        row.expectedGainPct(),
        row.expectedOptions(),
        row.expectedWillEfficiency(),
        row.expectedPoint(),
        row.expectedCost(),
        row.goldPerPercent(),
        row.missingPrices());
  }

  private static BigDecimal scale(double value, int digits) {
    return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP);
  }
}
