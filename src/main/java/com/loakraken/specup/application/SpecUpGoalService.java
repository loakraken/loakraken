package com.loakraken.specup.application;

import com.loakraken.character.application.CharacterResult;
import com.loakraken.character.application.CombatPowerSimulationService;
import com.loakraken.character.application.CombatPowerSimulationService.Change;
import com.loakraken.character.application.CombatPowerSimulationService.Kind;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CombatPowerReasons;
import com.loakraken.common.api.BusinessException;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.RefiningMaterialPriceService;
import com.loakraken.market.application.RelicBookPriceService;
import com.loakraken.refining.domain.RefiningRules;
import com.loakraken.specup.domain.SpecUpCost;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 스펙업 목표 경로(L31). 목표 전투력에 닿을 때까지 지금 상태에서 칸마다 '다음 한 단계'를 만들고, 효율 순(1만 골드당 전투력, 비용을 모르면 그 뒤에 Δ 순)으로
 * 가장 앞의 한 단계를 적용하기를 되풀이한다. 같은 칸을 여러 단계 올릴 수 있다. 모든 전투력은 시뮬레이터 결과이고(합산·추정 없음), 공식 API를 부르지 않는다.
 *
 * <p>비용: 시세가 없는 단계가 하나라도 있으면 그 단계부터 누적 비용은 null이다(0으로 더하지 않음). 보석 같은 칸의 둘째 단계부터는 직전 단계 매입가를 뺀
 * 증분이다(PM-백엔드 L31 판정 ①).
 */
@Service
public class SpecUpGoalService {
  public static final String RULE_VERSION = "spec-up-goal-v1";
  static final int MAX_STEPS = 30;
  static final Duration TIME_LIMIT = Duration.ofSeconds(2);
  static final Duration CACHE_TTL = Duration.ofMinutes(10);
  static final String REACHED = "REACHED";
  static final String ALREADY_REACHED = "ALREADY_REACHED";
  static final String NO_CANDIDATE = "NO_CANDIDATE";
  static final String STEP_LIMIT = "STEP_LIMIT";
  static final String TIME_LIMITED = "TIME_LIMIT";
  private static final Map<String, String> STOP_TEXT =
      Map.of(
          REACHED, "목표 전투력에 닿았어요.",
          ALREADY_REACHED, "이미 목표 전투력 이상이에요.",
          NO_CANDIDATE, "더 올릴 수 있는 항목이 없어 여기서 멈췄어요.",
          STEP_LIMIT, "단계가 너무 많아 30단계에서 멈췄어요. 목표를 낮춰 보세요.",
          TIME_LIMITED, "계산 시간이 길어 여기까지 고른 단계만 보여 드려요.");

  private final SpecUpService specUp;
  private final CombatPowerSimulationService simulator;
  private final MarketAuctionService auctions;
  private final RelicBookPriceService books;
  private final RefiningMaterialPriceService materials;
  private final SpecUpGoalStorePort store;
  private final LongSupplier nanoTime;
  private final ConcurrentHashMap<String, CompletableFuture<Goal>> flights =
      new ConcurrentHashMap<>();

  @Autowired
  public SpecUpGoalService(
      SpecUpService specUp,
      CombatPowerSimulationService simulator,
      MarketAuctionService auctions,
      RelicBookPriceService books,
      RefiningMaterialPriceService materials,
      SpecUpGoalStorePort store) {
    this(specUp, simulator, auctions, books, materials, store, System::nanoTime);
  }

  SpecUpGoalService(
      SpecUpService specUp,
      CombatPowerSimulationService simulator,
      MarketAuctionService auctions,
      RelicBookPriceService books,
      RefiningMaterialPriceService materials,
      SpecUpGoalStorePort store,
      LongSupplier nanoTime) {
    this.specUp = specUp;
    this.simulator = simulator;
    this.auctions = auctions;
    this.books = books;
    this.materials = materials;
    this.store = store;
    this.nanoTime = nanoTime;
  }

  /** 경로의 한 단계. 표시 필드는 스펙업 후보와 같은 뜻이고 change는 시뮬레이터 요청 changes[] 하나와 같다. */
  public record Step(
      int order,
      String candidateId,
      String kind,
      String target,
      String from,
      String to,
      String icon,
      String grade,
      String skillName,
      Change change,
      BigDecimal deltaCombatPower,
      BigDecimal combatPowerAfter,
      SpecUpCost cost,
      Long cumulativeCost,
      BigDecimal combatPowerPer10kGold) {}

  public record Goal(
      String characterName,
      String serverName,
      String className,
      BigDecimal combatPower,
      BigDecimal targetCombatPower,
      BigDecimal finalCombatPower,
      boolean reached,
      String stopReasonCode,
      String stopReason,
      Long totalCost,
      int unpricedSteps,
      String ruleVersion,
      String simulatorRuleVersion,
      Instant fetchedAt,
      Instant pricesAt,
      List<Step> steps) {}

  public Goal goal(String characterName, BigDecimal target) {
    if (!specUp.enabled()) throw new BusinessException(SpecUpErrorCode.SPEC_UP_DISABLED);
    var snapshot = simulator.snapshot(characterName);
    var profile = snapshot.details().profile();
    var before =
        profile.combatPower() == null
            ? null
            : profile.combatPower().setScale(2, RoundingMode.HALF_UP);
    var goal = target.setScale(2, RoundingMode.HALF_UP);
    if (before != null && goal.compareTo(before.multiply(BigDecimal.TWO)) > 0)
      throw new BusinessException(SpecUpErrorCode.SPEC_UP_GOAL_OUT_OF_RANGE);
    var gems = gemPrices();
    var refinePrices = materials.cached();
    var key =
        String.join(
            ":",
            profile.serverName(),
            profile.characterName(),
            goal.toPlainString(),
            String.valueOf(snapshot.fetchedAt() == null ? 0 : snapshot.fetchedAt().toEpochMilli()),
            String.valueOf(latest(gems)),
            String.valueOf(refinePrices.map(p -> p.observedAt().toEpochMilli()).orElse(0L)),
            RULE_VERSION,
            CombatPowerSimulationService.RULE_VERSION);
    var cached = store.find(key);
    if (cached.isPresent()) return cached.get();
    return single(
        key,
        () -> {
          var result = compute(snapshot, before, goal, gems, refinePrices);
          // 시간 상한으로 멈춘 부분 결과는 캐시하지 않는다(PM-프론트 L31 ③).
          if (!TIME_LIMITED.equals(result.stopReasonCode())) store.save(key, result, CACHE_TTL);
          return result;
        });
  }

  private Goal compute(
      CharacterResult snapshot,
      BigDecimal before,
      BigDecimal target,
      Map<String, MarketAuctionService.GemCell> gems,
      Optional<RefiningMaterialPriceService.Prices> refinePrices) {
    var pricesAt = new Instant[1];
    var steps = new ArrayList<Step>();
    var roleReason = CombatPowerSimulationService.roleReason(snapshot);
    if (roleReason != null || before == null) {
      var reason =
          roleReason != null
              ? CombatPowerReasons.classify(roleReason)
              : CombatPowerReasons.NO_OFFICIAL_COMBAT_POWER;
      return result(
          snapshot, before, target, before, reason.code(), reason.text(), null, 0, null, steps);
    }
    if (before.compareTo(target) >= 0)
      return result(snapshot, before, target, before, ALREADY_REACHED, null, 0L, 0, null, steps);

    var state = State.of(snapshot.details().loadout());
    var applied = new LinkedHashMap<String, Change>();
    var current = before;
    Long cumulative = 0L;
    int unpriced = 0;
    long started = nanoTime.getAsLong();
    String stop;
    while (true) {
      if (current.compareTo(target) >= 0) {
        stop = REACHED;
        break;
      }
      if (steps.size() >= MAX_STEPS) {
        stop = STEP_LIMIT;
        break;
      }
      if (nanoTime.getAsLong() - started > TIME_LIMIT.toNanos()) {
        stop = TIME_LIMITED;
        break;
      }
      Option best = null;
      for (var option : options(state, gems, refinePrices, pricesAt)) {
        var trial = new LinkedHashMap<>(applied);
        trial.put(option.slot(), option.change());
        var simulated = simulator.simulate(snapshot, List.copyOf(trial.values()));
        // 지금까지 적용한 단계는 모두 계산된 것이므로, 계산하지 못한 변경이 있으면 이 후보다.
        if (simulated.unsupportedCount() > 0 || simulated.after() == null) continue;
        var delta = simulated.after().subtract(current);
        if (delta.signum() <= 0) continue;
        var scored =
            option.scored(
                delta,
                simulated.after(),
                SpecUpCost.efficiency(
                    delta, option.cost() == null ? null : option.cost().amount()));
        if (best == null || ORDER.compare(scored, best) < 0) best = scored;
      }
      if (best == null) {
        stop = NO_CANDIDATE;
        break;
      }
      applied.put(best.slot(), best.change());
      state = state.after(best);
      current = best.after();
      var amount = best.cost() == null ? null : best.cost().amount();
      if (amount == null) {
        unpriced++;
        cumulative = null;
      } else if (cumulative != null) cumulative += amount;
      steps.add(
          new Step(
              steps.size() + 1,
              best.id(),
              best.change().kind().name(),
              best.target(),
              best.from(),
              best.to(),
              best.icon(),
              best.grade(),
              best.skillName(),
              best.change(),
              best.delta(),
              best.after(),
              best.cost(),
              cumulative,
              best.efficiency()));
    }
    return result(
        snapshot, before, target, current, stop, null, cumulative, unpriced, pricesAt[0], steps);
  }

  private static Goal result(
      CharacterResult snapshot,
      BigDecimal before,
      BigDecimal target,
      BigDecimal last,
      String code,
      String text,
      Long totalCost,
      int unpriced,
      Instant pricesAt,
      List<Step> steps) {
    var profile = snapshot.details().profile();
    return new Goal(
        profile.characterName(),
        profile.serverName(),
        profile.className(),
        before,
        target,
        last,
        last != null && last.compareTo(target) >= 0,
        code,
        text != null ? text : STOP_TEXT.get(code),
        totalCost,
        unpriced,
        RULE_VERSION,
        CombatPowerSimulationService.RULE_VERSION,
        snapshot.fetchedAt(),
        pricesAt,
        List.copyOf(steps));
  }

  /** 효율 있음(내림차순) → 비용 모름(Δ 내림차순) → id 순. 스펙업 가이드의 효율 순과 같다. */
  static final Comparator<Option> ORDER =
      Comparator.comparing((Option option) -> option.efficiency() == null ? 1 : 0)
          .thenComparing(Option::efficiency, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(Option::delta, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(Option::id);

  /** 지금 상태에서 칸마다 다음 한 단계. */
  private List<Option> options(
      State state,
      Map<String, MarketAuctionService.GemCell> gems,
      Optional<RefiningMaterialPriceService.Prices> refinePrices,
      Instant[] pricesAt) {
    var result = new ArrayList<Option>();
    for (var gem : state.gems().values()) {
      if (gem.level() >= 10) continue;
      int next = gem.level() + 1;
      String label = gem.level() + "레벨 " + (gem.kind() == null ? "보석" : gem.kind());
      String target =
          gem.skill() == null ? "보석 " + gem.slot() + "번 · " + label : gem.skill() + " · " + label;
      result.add(
          new Option(
              "GEM_LEVEL:" + gem.slot(),
              "GEM_LEVEL:" + gem.slot() + ":" + next,
              target,
              gem.level() + "레벨",
              next + "레벨",
              gem.icon(),
              gem.grade(),
              gem.skill(),
              new Change(
                  Kind.GEM_LEVEL,
                  gem.slot(),
                  null,
                  null,
                  next,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null),
              gemCost(gems, pricesAt, gem, next),
              null,
              null,
              null));
    }
    for (var item : state.refines().values()) {
      int next = item.refine() + 1;
      result.add(
          new Option(
              "EQUIPMENT_REFINE:" + item.index(),
              "EQUIPMENT_REFINE:" + item.index() + ":" + next,
              item.equipment().name(),
              "+" + item.refine(),
              "+" + next,
              item.equipment().icon(),
              item.equipment().grade(),
              null,
              new Change(
                  Kind.EQUIPMENT_REFINE,
                  null,
                  item.index(),
                  null,
                  null,
                  next,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null),
              item.refine() >= 10 && item.refine() < 25
                  ? specUp.refineCost(item.equipment(), item.refine(), refinePrices, pricesAt)
                  : SpecUpCost.REFINE_COST_UNCONFIRMED,
              null,
              null,
              null));
    }
    for (var item : state.advanced().values()) {
      int next = item.refine() + 1;
      result.add(
          new Option(
              "EQUIPMENT_ADVANCED_REFINE:" + item.index(),
              "EQUIPMENT_ADVANCED_REFINE:" + item.index() + ":" + next,
              item.equipment().name(),
              "상급 재련 " + item.refine() + "단계",
              "상급 재련 " + next + "단계",
              item.equipment().icon(),
              item.equipment().grade(),
              null,
              new Change(
                  Kind.EQUIPMENT_ADVANCED_REFINE,
                  null,
                  item.index(),
                  null,
                  next,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null),
              SpecUpCost.REFINE_COST_UNCONFIRMED,
              null,
              null,
              null));
    }
    if (state.quality() != null) {
      var weapon = state.quality();
      int next = Math.min(100, (weapon.refine() / 10 + 1) * 10);
      result.add(
          new Option(
              "EQUIPMENT_QUALITY:" + weapon.index(),
              "EQUIPMENT_QUALITY:" + weapon.index() + ":" + next,
              weapon.equipment().name(),
              "품질 " + weapon.refine(),
              "품질 " + next,
              weapon.equipment().icon(),
              weapon.equipment().grade(),
              null,
              new Change(
                  Kind.EQUIPMENT_QUALITY,
                  null,
                  weapon.index(),
                  null,
                  null,
                  null,
                  next,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null),
              SpecUpCost.QUALITY_COST_UNCONFIRMED,
              null,
              null,
              null));
    }
    for (var engraving : state.engravings().values()) {
      int next = engraving.level() + 1;
      result.add(
          new Option(
              "ENGRAVING_LEVEL:" + engraving.name(),
              "ENGRAVING_LEVEL:" + engraving.name() + ":" + next,
              engraving.name() + " 각인",
              engraving.level() + "레벨",
              next + "레벨",
              null,
              engraving.grade(),
              null,
              new Change(
                  Kind.ENGRAVING_LEVEL,
                  null,
                  null,
                  null,
                  next,
                  null,
                  null,
                  null,
                  null,
                  engraving.name(),
                  null,
                  null,
                  null,
                  null),
              SpecUpService.bookCost(engraving.name(), engraving.grade(), books::price, pricesAt),
              null,
              null,
              null));
    }
    return result;
  }

  /**
   * 보석 한 단계 비용. 처음 단계는 스펙업 후보와 같다(다음 레벨 매입가 − 지금 보석 판매 기대가, 귀속이면 빼지 않음). 같은 칸 둘째 단계부터는 이번 레벨 매입가 −
   * 직전 레벨 매입가다.
   */
  private static SpecUpCost gemCost(
      Map<String, MarketAuctionService.GemCell> gems, Instant[] pricesAt, GemSlot gem, int next) {
    if (gem.level() == gem.original())
      return SpecUpService.gemCost(gems, pricesAt, gem.priceKind(), gem.level(), next, gem.bound());
    if (gem.priceKind() == null) return SpecUpCost.NO_PRICE_SOURCE;
    if (gems.isEmpty()) return SpecUpCost.PRICE_PENDING;
    if (!gems.containsKey(gem.priceKind() + ":" + next)) return SpecUpCost.NO_PRICE_SOURCE;
    var nextCell = gems.get(gem.priceKind() + ":" + next);
    var previousCell = gems.get(gem.priceKind() + ":" + gem.level());
    if (nextCell.cell() == null
        || nextCell.cell().minBuyPrice() == null
        || previousCell == null
        || previousCell.cell() == null
        || previousCell.cell().minBuyPrice() == null) return SpecUpCost.PRICE_PENDING;
    SpecUpService.observed(pricesAt, nextCell);
    SpecUpService.observed(pricesAt, previousCell);
    return SpecUpCost.gemStep(nextCell.cell().minBuyPrice(), previousCell.cell().minBuyPrice());
  }

  private Map<String, MarketAuctionService.GemCell> gemPrices() {
    var prices = new HashMap<String, MarketAuctionService.GemCell>();
    for (var cell : auctions.cachedGems()) prices.put(cell.kind() + ":" + cell.level(), cell);
    return prices;
  }

  private static long latest(Map<String, MarketAuctionService.GemCell> gems) {
    long latest = 0;
    for (var cell : gems.values())
      if (cell.cell() != null && cell.cell().observedAt() != null)
        latest = Math.max(latest, cell.cell().observedAt().toEpochMilli());
    return latest;
  }

  private Goal single(String key, java.util.function.Supplier<Goal> loader) {
    var future = new CompletableFuture<Goal>();
    var existing = flights.putIfAbsent(key, future);
    if (existing != null) {
      try {
        return existing.join();
      } catch (CompletionException exception) {
        if (exception.getCause() instanceof RuntimeException cause) throw cause;
        throw exception;
      }
    }
    try {
      var result = loader.get();
      future.complete(result);
      return result;
    } catch (RuntimeException | Error exception) {
      future.completeExceptionally(exception);
      throw exception;
    } finally {
      flights.remove(key, future);
    }
  }

  /** 다음 한 단계 후보. delta·after·efficiency는 시뮬레이터로 잰 뒤 채운다. */
  record Option(
      String slot,
      String id,
      String target,
      String from,
      String to,
      String icon,
      String grade,
      String skillName,
      Change change,
      SpecUpCost cost,
      BigDecimal delta,
      BigDecimal after,
      BigDecimal efficiency) {
    Option scored(BigDecimal delta, BigDecimal after, BigDecimal efficiency) {
      return new Option(
          slot,
          id,
          target,
          from,
          to,
          icon,
          grade,
          skillName,
          change,
          cost,
          delta,
          after,
          efficiency);
    }
  }

  record GemSlot(
      int slot,
      String kind,
      String priceKind,
      int original,
      int level,
      boolean bound,
      String skill,
      String icon,
      String grade) {}

  /** refine은 재련 단계(상급 재련이면 상급 단계, 품질이면 품질 값)다. */
  record Item(int index, CharacterLoadout.Equipment equipment, int refine) {}

  record Engraving(String name, String grade, int level) {}

  /** 지금까지 적용한 단계를 반영한 칸별 상태. 다음 단계가 없는 칸은 빠진다. */
  record State(
      Map<Integer, GemSlot> gems,
      Map<Integer, Item> refines,
      Map<Integer, Item> advanced,
      Item quality,
      Map<String, Engraving> engravings) {
    static State of(CharacterLoadout loadout) {
      var gems = new LinkedHashMap<Integer, GemSlot>();
      if (loadout.gems() != null) {
        var skills = new HashMap<Integer, String>();
        var effects = SpecUpService.gemEffects(loadout);
        if (loadout.gems().effects() != null)
          for (var skill : loadout.gems().effects().skills())
            if (skill != null && skill.gemSlot() != null && skill.name() != null)
              skills.putIfAbsent(skill.gemSlot(), skill.name());
        for (var gem : loadout.gems().items()) {
          if (gem == null || gem.slot() == null || gem.level() == null || gem.level() >= 10)
            continue;
          var matcher = gem.name() == null ? null : SpecUpService.GEM.matcher(gem.name());
          String kind = matcher != null && matcher.find() ? matcher.group(2) : null;
          gems.put(
              gem.slot(),
              new GemSlot(
                  gem.slot(),
                  kind,
                  SpecUpService.priceKind(kind, effects.get(gem.slot())),
                  gem.level(),
                  gem.level(),
                  SpecUpService.bound(gem),
                  skills.get(gem.slot()),
                  gem.icon(),
                  gem.grade()));
        }
      }
      var refines = new LinkedHashMap<Integer, Item>();
      var advanced = new LinkedHashMap<Integer, Item>();
      Item quality = null;
      for (int index = 0; index < loadout.equipment().size(); index++) {
        var item = loadout.equipment().get(index);
        if (item == null || item.detail() == null) continue;
        if (SpecUpService.WEAPON_ARMOR.contains(item.type())) {
          var refine = item.detail().refineLevel();
          if (refine != null && refine >= 10 && refine < 25)
            refines.put(index, new Item(index, item, refine));
          var level = item.detail().advancedRefineLevel();
          // 전율 장비에는 상급 재련이 없다(E21).
          if (level != null
              && level < 40
              && SpecUpService.refineGrade(item.name()) != RefiningRules.Grade.JEONYUL)
            advanced.put(index, new Item(index, item, level));
        }
        if (quality == null
            && "무기".equals(item.type())
            && item.detail().quality() != null
            && item.detail().quality() < 100)
          quality = new Item(index, item, item.detail().quality());
      }
      var engravings = new LinkedHashMap<String, Engraving>();
      if (loadout.engravings() != null)
        for (var engraving : loadout.engravings().arkPassiveEffects())
          if (engraving != null
              && engraving.name() != null
              && engraving.level() != null
              && engraving.level() < 4)
            engravings.put(
                engraving.name(),
                new Engraving(engraving.name(), engraving.grade(), engraving.level()));
      return new State(gems, refines, advanced, quality, engravings);
    }

    /** option 단계를 적용한 다음 상태. 끝 단계에 닿은 칸은 뺀다. */
    State after(Option option) {
      var gems = new LinkedHashMap<>(this.gems);
      var refines = new LinkedHashMap<>(this.refines);
      var advanced = new LinkedHashMap<>(this.advanced);
      var quality = this.quality;
      var engravings = new LinkedHashMap<>(this.engravings);
      var change = option.change();
      switch (change.kind()) {
        case GEM_LEVEL -> {
          var gem = gems.get(change.slot());
          if (change.level() >= 10) gems.remove(change.slot());
          else
            gems.put(
                change.slot(),
                new GemSlot(
                    gem.slot(),
                    gem.kind(),
                    gem.priceKind(),
                    gem.original(),
                    change.level(),
                    gem.bound(),
                    gem.skill(),
                    gem.icon(),
                    gem.grade()));
        }
        case EQUIPMENT_REFINE -> {
          var item = refines.get(change.index());
          if (change.refine() >= 25) refines.remove(change.index());
          else
            refines.put(change.index(), new Item(item.index(), item.equipment(), change.refine()));
        }
        case EQUIPMENT_ADVANCED_REFINE -> {
          var item = advanced.get(change.index());
          if (change.level() >= 40) advanced.remove(change.index());
          else
            advanced.put(change.index(), new Item(item.index(), item.equipment(), change.level()));
        }
        case EQUIPMENT_QUALITY ->
            quality =
                change.quality() >= 100
                    ? null
                    : new Item(quality.index(), quality.equipment(), change.quality());
        case ENGRAVING_LEVEL -> {
          var engraving = engravings.get(change.name());
          if (change.level() >= 4) engravings.remove(change.name());
          else
            engravings.put(
                change.name(), new Engraving(engraving.name(), engraving.grade(), change.level()));
        }
        default -> {}
      }
      return new State(gems, refines, advanced, quality, engravings);
    }
  }
}
