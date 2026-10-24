package com.loakraken.specup.application;

import com.loakraken.character.application.CharacterResult;
import com.loakraken.character.application.CombatPowerSimulationService;
import com.loakraken.character.application.CombatPowerSimulationService.Change;
import com.loakraken.character.application.CombatPowerSimulationService.Kind;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CombatPowerGemModel;
import com.loakraken.character.domain.CombatPowerPolishValues;
import com.loakraken.character.domain.CombatPowerReasons;
import com.loakraken.common.api.BusinessException;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.RefiningMaterialPriceService;
import com.loakraken.market.application.RelicBookPriceService;
import com.loakraken.refining.application.RefiningPlanService;
import com.loakraken.refining.domain.RefiningPlan;
import com.loakraken.refining.domain.RefiningRules;
import com.loakraken.specup.domain.SpecUpCost;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 스펙업 가이드(#315, 계약 v0). 캐릭터 캐시의 현재 세팅에서 후보 업그레이드마다 전투력 시뮬레이터를 <b>변화 하나</b>로 돌려 Δ전투력을 구하고(후보끼리 더하지
 * 않음), 시세 캐시로 비용을 붙여 1만 골드당 전투력으로 정렬한다. 공식 API를 부르지 않는다. 계산할 수 없는 후보도 목록에 이유와 함께 남긴다.
 */
@Service
public class SpecUpService {
  public static final String RULE_VERSION = "spec-up-v1";
  static final Pattern GEM = Pattern.compile("(\\d+)레벨 (\\S+)의 보석");
  static final Set<String> WEAPON_ARMOR = Set.of("무기", "투구", "상의", "하의", "장갑", "어깨");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final CombatPowerSimulationService simulator;
  private final MarketAuctionService auctions;
  private final RelicBookPriceService books;
  private final RefiningPlanService plans;
  private final RefiningMaterialPriceService materials;
  private final CombatPowerGemModel.Coefficients gemCoefficients;
  private final SpecUpKrakenScore kraken;
  private final CombatPowerPolishValues polishValues;
  private final boolean enabled;

  /** 빈 칸 후보 레벨: 기본 공격력 %가 확인된 6~10레벨만(1~5레벨은 계수표에 근거가 없어 계산하지 않는다). */
  static final int EMPTY_SLOT_MIN_LEVEL = 6;

  public SpecUpService(
      CombatPowerSimulationService simulator,
      MarketAuctionService auctions,
      RelicBookPriceService books,
      RefiningPlanService plans,
      RefiningMaterialPriceService materials,
      CombatPowerGemModel.Coefficients gemCoefficients,
      SpecUpKrakenScore kraken,
      CombatPowerPolishValues polishValues,
      @Value("${spec-up.enabled:false}") boolean enabled) {
    this.simulator = simulator;
    this.auctions = auctions;
    this.books = books;
    this.plans = plans;
    this.materials = materials;
    this.gemCoefficients = gemCoefficients;
    this.kraken = kraken;
    this.polishValues = polishValues;
    this.enabled = enabled;
  }

  /** KRAKEN: Δ크라켄 점수 ÷ 비용(비교 기준 B 스펙업 추천과 같은 순서). 점수를 낼 수 없으면 EFFICIENCY로 되돌린다. */
  public enum Sort {
    EFFICIENCY,
    DELTA,
    KRAKEN
  }

  /** change가 null이면 시뮬레이터 입력으로 만들 수 없는 종류(한 줄로 알림)다. */
  public record Candidate(
      String id,
      String kind,
      String target,
      String from,
      String to,
      String icon,
      String grade,
      String skillName,
      String suggestedTo,
      Change change,
      String status,
      BigDecimal deltaCombatPower,
      BigDecimal deltaPercent,
      SpecUpCost cost,
      BigDecimal combatPowerPer10kGold,
      String reasonCode,
      String reason,
      String reasonDetail,
      Boolean emptySlot,
      BigDecimal baseAttackPctFrom,
      BigDecimal baseAttackPctTo,
      BigDecimal deltaKrakenScore,
      BigDecimal krakenScorePer100kGold,
      String gemType) {
    /**
     * Δ크라켄 점수와 10만 골드당 점수를 붙인다. 10만 골드당은 같은 Δ에서 만든다(정렬 키 = 보이는 값). 전투력 계산이 UNSUPPORTED라 비용이 비었어도
     * Δ크라켄 점수가 있으면 계획한 비용(planned)을 준다 — 그래야 KRAKEN 정렬에서 비용으로 나눌 수 있다.
     */
    Candidate withKraken(BigDecimal delta, SpecUpCost planned) {
      return withKraken(delta, delta == null ? null : delta.doubleValue(), planned);
    }

    /**
     * raw는 반올림 전 Δ크라켄 점수. 10만 골드당 = raw ÷ 반올림 전 비용 × 10만을 소수 둘째 자리로(비교 기준 B 원본과 같은 계산 순서 — 카르마·스톤처럼
     * 비용이 정수가 아니거나 Δ가 작으면 반올림한 값끼리 나누면 둘째 자리가 달라진다).
     */
    Candidate withKraken(BigDecimal delta, Double raw, SpecUpCost planned) {
      var cost = this.cost == null && delta != null ? planned : this.cost;
      BigDecimal per100k = null;
      if (delta != null
          && delta.signum() > 0
          && raw != null
          && cost != null
          && "OK".equals(cost.status())
          && cost.exact() != null
          && cost.exact() > 0) {
        double value = raw / cost.exact() * 100_000;
        if (Double.isFinite(value) && value > 0) per100k = SpecUpKrakenScore.roundEfficiency(value);
      }
      return new Candidate(
          id,
          kind,
          target,
          from,
          to,
          icon,
          grade,
          skillName,
          suggestedTo,
          change,
          status,
          deltaCombatPower,
          deltaPercent,
          cost,
          combatPowerPer10kGold,
          reasonCode,
          reason,
          reasonDetail,
          emptySlot,
          baseAttackPctFrom,
          baseAttackPctTo,
          delta,
          per100k,
          gemType);
    }
  }

  public record Guide(
      String characterName,
      String serverName,
      String className,
      BigDecimal combatPower,
      String ruleVersion,
      String simulatorRuleVersion,
      Instant fetchedAt,
      Instant pricesAt,
      String sort,
      int limit,
      int total,
      List<Candidate> candidates,
      BigDecimal krakenScore,
      String krakenRuleVersion,
      boolean cooldownGemsIncluded,
      String krakenSortUnavailableReason) {}

  boolean enabled() {
    return enabled;
  }

  public Guide guide(String characterName, Sort sort, int limit) {
    return guide(characterName, sort, limit, null);
  }

  /** includeCooldownGems가 null이면 KRAKEN은 작열(쿨) 보석 후보를 빼고, 그 밖 정렬은 넣는다(비교 기준 B 기본 조건, 기존 동작 유지). */
  public Guide guide(String characterName, Sort sort, int limit, Boolean includeCooldownGems) {
    if (!enabled) throw new BusinessException(SpecUpErrorCode.SPEC_UP_DISABLED);
    return guide(simulator.snapshot(characterName), sort, limit, includeCooldownGems);
  }

  /** 이미 읽은 캐릭터 캐시로 목록을 만든다(줄 상세 #544가 같은 세팅으로 후보를 다시 찾을 때). */
  Guide guide(CharacterResult snapshot, Sort sort, int limit) {
    return guide(snapshot, sort, limit, null);
  }

  Guide guide(CharacterResult snapshot, Sort requested, int limit, Boolean includeCooldownGems) {
    var profile = snapshot.details().profile();
    var loadout = snapshot.details().loadout();
    var prices = new HashMap<String, MarketAuctionService.GemCell>();
    for (var cell : auctions.cachedGems()) prices.put(cell.kind() + ":" + cell.level(), cell);
    var pricesAt = new Instant[1];
    var planned = new ArrayList<Planned>();
    gems(loadout, gemCoefficients, prices, pricesAt, planned);
    quality(loadout, planned);
    engravings(loadout, books::price, books::icon, pricesAt, planned);
    refines(loadout, materials.cached(), pricesAt, planned);
    armGuard(loadout, planned);
    var krakenContext = kraken.context(snapshot);
    others(loadout, planned, krakenContext);

    // 점수를 낼 수 없으면 KRAKEN도 효율 순으로 되돌리고 그 이유를 응답에 둔다.
    var sort = requested == Sort.KRAKEN && !krakenContext.available() ? Sort.EFFICIENCY : requested;
    boolean cooldownGemsIncluded =
        includeCooldownGems != null ? includeCooldownGems : requested != Sort.KRAKEN;
    var ok = new ArrayList<Candidate>();
    var unsupported = new ArrayList<Candidate>();
    // 딜러가 아니면 모든 후보를 역할 이유로 UNSUPPORTED(PM-백엔드 P0 10-11 20:23).
    var roleReason = CombatPowerSimulationService.roleReason(snapshot);
    for (var plan : planned) {
      if (!cooldownGemsIncluded && "COOLDOWN".equals(plan.gemType())) continue;
      var candidate =
          roleReason != null
              ? plan.candidate(
                  "UNSUPPORTED",
                  null,
                  null,
                  null,
                  null,
                  CombatPowerReasons.classify(roleReason),
                  null)
              : evaluate(snapshot, plan);
      var raw = krakenDelta(krakenContext, loadout, plan);
      candidate =
          candidate.withKraken(
              raw == null ? null : SpecUpKrakenScore.round2(raw), raw, plan.cost());
      // KRAKEN은 Δ크라켄 점수가 있으면 전투력 계산과 상관없이 순위에 넣는다.
      boolean ranked =
          "OK".equals(candidate.status())
              || (sort == Sort.KRAKEN && candidate.deltaKrakenScore() != null);
      (ranked ? ok : unsupported).add(candidate);
    }
    ok.sort(order(sort));
    int total = ok.size();
    var result = new ArrayList<Candidate>(ok.subList(0, Math.min(limit, ok.size())));
    result.addAll(unsupported);
    return new Guide(
        profile.characterName(),
        profile.serverName(),
        profile.className(),
        profile.combatPower() == null
            ? null
            : profile.combatPower().setScale(2, RoundingMode.HALF_UP),
        RULE_VERSION,
        CombatPowerSimulationService.RULE_VERSION,
        snapshot.fetchedAt(),
        pricesAt[0],
        sort.name(),
        limit,
        total,
        List.copyOf(result),
        krakenContext.shown(),
        krakenContext.available() ? SpecUpKrakenScore.RULE_VERSION : null,
        cooldownGemsIncluded,
        krakenContext.unavailable());
  }

  /**
   * 후보 하나의 Δ크라켄 점수 = (적용 뒤 점수 − 지금 점수)를 소수 둘째 자리로 반올림(비교 기준 B 스펙업 ▲와 같은 정의 — 화면 점수끼리의 차가 아니라 반올림 전
   * 값의 차, 레퍼런스 26줄 대조). 계산 못 하면 null.
   */
  /** 반올림 전 Δ크라켄 점수(응답 값은 round2). */
  private Double krakenDelta(
      SpecUpKrakenScore.Context context, CharacterLoadout loadout, Planned plan) {
    return plan.kraken() != null
        ? kraken.delta(context, plan.kraken())
        : kraken.delta(context, loadout, plan.change());
  }

  /** 효율 순: 효율 있음(내림차순) → 비용 모름(Δ 내림차순). Δ 순: Δ 내림차순. 같으면 id 순. */
  static Comparator<Candidate> order(Sort sort) {
    Comparator<Candidate> byDelta =
        Comparator.comparing(
            Candidate::deltaCombatPower, Comparator.nullsLast(Comparator.reverseOrder()));
    Comparator<Candidate> tail = Comparator.comparing(Candidate::id);
    if (sort == Sort.DELTA) return byDelta.thenComparing(tail);
    if (sort == Sort.KRAKEN) {
      // ① Δ > 0·비용 있음: 10만 골드당 내림차순 ② Δ > 0·10만 골드당 없음(비용 0골드와 비용 모름을 나누지 않음): Δ 내림차순 ③ 나머지: 효율
      // 순. 같으면 id 순(PM 확정 10-18 — 효율 없는 줄은 효율 있는 줄 뒤, 그 안에서는 Δ 큰 순).
      Comparator<Candidate> group =
          Comparator.comparingInt(
              candidate -> {
                if (candidate.krakenScorePer100kGold() != null) return 0;
                if (candidate.deltaKrakenScore() != null
                    && candidate.deltaKrakenScore().signum() > 0) return 1;
                return 2;
              });
      return group
          .thenComparing(
              Candidate::krakenScorePer100kGold, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(
              Candidate::deltaKrakenScore, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(order(Sort.EFFICIENCY));
    }
    return Comparator.comparing(
            (Candidate candidate) -> candidate.combatPowerPer10kGold() == null ? 1 : 0)
        .thenComparing(
            Candidate::combatPowerPer10kGold, Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing(byDelta)
        .thenComparing(tail);
  }

  private Candidate evaluate(CharacterResult snapshot, Planned plan) {
    if (plan.change() == null)
      return plan.candidate(
          "UNSUPPORTED",
          null,
          null,
          null,
          null,
          plan.kraken() != null
              ? CombatPowerReasons.SCORE_ONLY
              : CombatPowerReasons.MODEL_UNCONFIRMED,
          null);
    var result = simulator.simulate(snapshot, List.of(plan.change()));
    var contribution = result.contributions().getFirst();
    if (contribution.status() != CombatPowerSimulationService.Status.OK) {
      var reason = CombatPowerReasons.classify(contribution.basis());
      return plan.candidate(
          "UNSUPPORTED",
          null,
          null,
          null,
          null,
          reason,
          reason.text().equals(contribution.basis()) ? null : contribution.basis());
    }
    var delta = contribution.delta();
    var percent =
        result.before() == null || result.before().signum() == 0
            ? null
            : delta.multiply(HUNDRED).divide(result.before(), 3, RoundingMode.HALF_UP);
    // 비용 근거가 없는 후보도 500이 아니라 비용·효율 없이 낸다(P0 10-12: 재련이 OK가 된 뒤 비용 null로 NPE).
    var cost = plan.cost();
    return plan.candidate(
        "OK",
        delta,
        percent,
        cost,
        SpecUpCost.efficiency(delta, cost == null ? null : cost.amount()),
        null,
        null);
  }

  /** 보석 +1: 슬롯마다 현재 레벨 + 1(10 미만). 대상 이름은 보석이 붙은 스킬(PM-프론트 16:27 ③). */
  private static void gems(
      CharacterLoadout loadout,
      CombatPowerGemModel.Coefficients coefficients,
      Map<String, MarketAuctionService.GemCell> prices,
      Instant[] pricesAt,
      List<Planned> planned) {
    if (loadout.gems() == null) return;
    var skills = new HashMap<Integer, String>();
    var effects = gemEffects(loadout);
    if (loadout.gems().effects() != null)
      for (var skill : loadout.gems().effects().skills())
        if (skill != null && skill.gemSlot() != null && skill.name() != null)
          skills.putIfAbsent(skill.gemSlot(), skill.name());
    for (var gem : loadout.gems().items()) {
      if (gem == null || gem.slot() == null || gem.level() == null || gem.level() >= 10) continue;
      var matcher = gem.name() == null ? null : GEM.matcher(gem.name());
      String kind = matcher != null && matcher.find() ? matcher.group(2) : null;
      String priceKind = priceKind(kind, effects.get(gem.slot()));
      String skill = skills.get(gem.slot());
      String label = gem.level() + "레벨 " + (kind == null ? "보석" : kind);
      var tier = kind == null ? null : coefficients.tierOf(kind);
      String target = skill == null ? "보석 " + gem.slot() + "번 · " + label : skill + " · " + label;
      // 슬롯마다 다음 레벨부터 10까지 단계별 후보를 각각 낸다(PM-프론트·PM-백엔드 16:37 ①).
      for (int next = gem.level() + 1; next <= 10; next++)
        planned.add(
            new Planned(
                "GEM_LEVEL:" + gem.slot() + ":" + next,
                Kind.GEM_LEVEL.name(),
                target,
                gem.level() + "레벨",
                next + "레벨",
                gem.icon(),
                gem.grade(),
                skill,
                change(Kind.GEM_LEVEL, gem.slot(), null, next, null, null),
                gemCost(prices, pricesAt, priceKind, gem.level(), next, bound(gem)),
                false,
                baseAttackPct(tier, gem.level()),
                baseAttackPct(tier, next),
                gemType(kind, effects.get(gem.slot()))));
    }
    emptySlot(loadout.gems(), coefficients, prices, pricesAt, planned);
  }

  /**
   * 빈 보석 칸 = '보석 장착' 후보(사용자 지적 10-17: 보석이 없는 캐릭터는 스펙업 결과가 비어 있었음). 4티어 보석은 어느 칸이든 전투력 계수가 같아 칸마다 같은
   * 값이므로 가장 앞 빈 칸 하나로 6~10레벨을 낸다(같은 줄을 빈 칸 수만큼 반복하지 않음). 비용은 겁화(작열 제외 기본) 목표 레벨 즉시 구매 최저가, 팔 보석이
   * 없으니 판매가를 빼지 않는다(GEM_BUY).
   */
  static void emptySlot(
      CharacterLoadout.Gems gems,
      CombatPowerGemModel.Coefficients coefficients,
      Map<String, MarketAuctionService.GemCell> prices,
      Instant[] pricesAt,
      List<Planned> planned) {
    // 보석 정보를 받지 못했으면 빈 칸인지 모른다(CombatPowerGemModel.observed).
    if (!CombatPowerGemModel.observed(gems)) return;
    var occupied = new TreeSet<Integer>();
    for (var gem : gems.items()) if (gem != null && gem.slot() != null) occupied.add(gem.slot());
    var empty = new ArrayList<Integer>();
    for (int slot = 0; slot < CombatPowerGemModel.SLOT_COUNT; slot++)
      if (!occupied.contains(slot)) empty.add(slot);
    if (empty.isEmpty()) return;
    var tier = coefficients.tierOf(CombatPowerGemModel.EMPTY_SLOT_KIND);
    int slot = empty.getFirst();
    String target = "빈 보석 칸 " + empty.size() + "개";
    for (int next = EMPTY_SLOT_MIN_LEVEL; next <= 10; next++)
      planned.add(
          new Planned(
              "GEM_LEVEL:" + slot + ":" + next,
              Kind.GEM_LEVEL.name(),
              target,
              "빈 칸",
              next + "레벨 " + CombatPowerGemModel.EMPTY_SLOT_KIND,
              null,
              null,
              null,
              change(Kind.GEM_LEVEL, slot, null, next, null, null),
              gemCost(prices, pricesAt, CombatPowerGemModel.EMPTY_SLOT_KIND, 0, next, false),
              true,
              BigDecimal.ZERO,
              baseAttackPct(tier, next),
              "DAMAGE"));
  }

  /**
   * 보석 하나의 기본 공격력 %(보석 계수표 combat-power/gem-coefficients.json: 6·10레벨은 공식 보석 효과 확인값, 7~9레벨은 공개 분석 값
   * — 비교 기준 5명 대조 일치). 6~10레벨 0.45·0.60·0.80·1.00·1.20, 1~5레벨은 근거가 없어 null.
   */
  static BigDecimal baseAttackPct(CombatPowerGemModel.Tier tier, int level) {
    if (tier == null || !tier.supported()) return null;
    return tier.levels().stream()
        .filter(value -> value.level() == level)
        .findFirst()
        // 1~5레벨은 계수표 값이 null이다. map을 findFirst 앞에 두면 null 원소에서 NPE(스펙업 전체 500)가 난다.
        .map(CombatPowerGemModel.Level::baseAttackPct)
        .orElse(null);
  }

  /** 보석 칸 → 공식 보석 효과 문구(armory Effects.Skills의 Description). */
  static Map<Integer, List<String>> gemEffects(CharacterLoadout loadout) {
    var effects = new HashMap<Integer, List<String>>();
    if (loadout.gems() != null && loadout.gems().effects() != null)
      for (var skill : loadout.gems().effects().skills())
        if (skill != null && skill.gemSlot() != null)
          effects.putIfAbsent(skill.gemSlot(), skill.description());
    return effects;
  }

  /**
   * 비용에 쓸 시세 종류. 광휘 보석은 공식 경매장 보석 검색에 종류가 없어("광휘" 검색 0건, 계산 검증 2026-10-13 확인) 그 칸 효과와 같은 종류의 시세를
   * 쓴다: "피해 N% 증가" → 겁화, "재사용 대기시간 N% 감소" → 작열. 효과를 읽지 못하면 null(비용 없음, NO_PRICE_SOURCE).
   */
  static String priceKind(String kind, List<String> effects) {
    if (!"광휘".equals(kind)) return kind;
    if (effects == null) return null;
    for (var line : effects) {
      if (line == null) continue;
      if (line.contains("재사용 대기시간") && line.contains("감소")) return "작열";
      if (line.contains("피해") && line.contains("증가")) return "겁화";
    }
    return null;
  }

  static SpecUpCost gemCost(
      Map<String, MarketAuctionService.GemCell> prices,
      Instant[] pricesAt,
      String kind,
      int level,
      int next,
      boolean bound) {
    if (kind == null) return SpecUpCost.NO_PRICE_SOURCE;
    // 시세 표가 비었으면 아직 받지 못한 것이다(PENDING). 표는 있는데 그 칸만 없으면 시세 출처가 없는 종류다.
    if (prices.isEmpty()) return SpecUpCost.PRICE_PENDING;
    if (!prices.containsKey(kind + ":" + next)) return SpecUpCost.NO_PRICE_SOURCE;
    var nextCell = prices.get(kind + ":" + next);
    if (nextCell.cell() == null || nextCell.cell().minBuyPrice() == null)
      return SpecUpCost.PRICE_PENDING;
    observed(pricesAt, nextCell);
    Long current = null;
    if (!bound && level >= 1) {
      var currentCell = prices.get(kind + ":" + level);
      // 지금 레벨 매물 최저가가 없으면 뺄 값을 몰라 PRICE_PENDING이다(PM-백엔드 10-12 05:01 P2).
      if (currentCell == null
          || currentCell.cell() == null
          || currentCell.cell().minBuyPrice() == null) return SpecUpCost.PRICE_PENDING;
      observed(pricesAt, currentCell);
      current = currentCell.cell().minBuyPrice();
    }
    return SpecUpCost.gem(nextCell.cell().minBuyPrice(), current, bound);
  }

  static void observed(Instant[] pricesAt, MarketAuctionService.GemCell cell) {
    var at = cell.cell().observedAt();
    if (at != null && (pricesAt[0] == null || at.isBefore(pricesAt[0]))) pricesAt[0] = at;
  }

  /** 귀속 보석은 팔 수 없다(영수증과 같은 판정: 이름의 "(귀속)" 또는 공식 툴팁 거래 줄, #560). */
  static boolean bound(CharacterLoadout.Gem gem) {
    return com.loakraken.character.domain.GemTrade.bound(gem);
  }

  /** 무기 품질: 지금보다 높은 다음 10 단위 값과 100(추가 피해 식이 0~100 전부를 다룸). */
  private static void quality(CharacterLoadout loadout, List<Planned> planned) {
    for (int index = 0; index < loadout.equipment().size(); index++) {
      var item = loadout.equipment().get(index);
      if (item == null || !"무기".equals(item.type()) || item.detail() == null) continue;
      var current = item.detail().quality();
      if (current == null || current >= 100) continue;
      var targets = new TreeSet<Integer>();
      targets.add(Math.min(100, (current / 10 + 1) * 10));
      targets.add(100);
      for (var target : targets)
        planned.add(
            new Planned(
                "EQUIPMENT_QUALITY:" + index + ":" + target,
                Kind.EQUIPMENT_QUALITY.name(),
                item.name(),
                "품질 " + current,
                "품질 " + target,
                item.icon(),
                item.grade(),
                null,
                change(Kind.EQUIPMENT_QUALITY, null, index, null, null, target),
                SpecUpCost.QUALITY_COST_UNCONFIRMED));
      return;
    }
  }

  /**
   * 유물 각인서 +1: 장착 각인마다 레벨 + 1(4 미만). 비용(L30) = 5권 × 유물 각인서 즉시 구매 최저가(예열 캐시만, 없으면 PRICE_PENDING).
   * 유물이 아닌 각인은 권수·시세 근거가 없어 UNKNOWN이다. 공식 각인 응답에는 아이콘이 없어 같은 이름 유물 각인서의 공식 아이콘(시세 캐시)을 쓰고, 캐시에 없으면
   * null이다(아이콘을 위한 공식 호출 없음, PM 판정 10-15).
   */
  private static void engravings(
      CharacterLoadout loadout,
      java.util.function.Function<String, java.util.Optional<RelicBookPriceService.Price>>
          bookPrice,
      java.util.function.Function<String, java.util.Optional<String>> bookIcon,
      Instant[] pricesAt,
      List<Planned> planned) {
    if (loadout.engravings() == null) return;
    for (var engraving : loadout.engravings().arkPassiveEffects()) {
      if (engraving == null || engraving.name() == null || engraving.level() == null) continue;
      int max = engravingMaxLevel(engraving.grade());
      // 다음 레벨부터 등급 최대 레벨까지 줄을 따로(비교 기준 B처럼 누적 줄). 기존 +1 줄 id·값은 그대로.
      for (int next = engraving.level() + 1; next <= max; next++)
        planned.add(
            new Planned(
                "ENGRAVING_LEVEL:" + engraving.name() + ":" + next,
                Kind.ENGRAVING_LEVEL.name(),
                engraving.name() + " 각인",
                engraving.level() + "레벨",
                next + "레벨",
                bookIcon.apply(engraving.name()).orElse(null),
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
                bookCost(
                    engraving.name(),
                    engraving.grade(),
                    bookPrice,
                    pricesAt,
                    next - engraving.level())));
    }
  }

  static SpecUpCost bookCost(
      String name,
      String grade,
      java.util.function.Function<String, java.util.Optional<RelicBookPriceService.Price>>
          bookPrice,
      Instant[] pricesAt) {
    return bookCost(name, grade, bookPrice, pricesAt, 1);
  }

  /** levels: 올리는 레벨 수(각인서 5권 × levels). */
  static SpecUpCost bookCost(
      String name,
      String grade,
      java.util.function.Function<String, java.util.Optional<RelicBookPriceService.Price>>
          bookPrice,
      Instant[] pricesAt,
      int levels) {
    if (!RelicBookPriceService.GRADE.equals(grade)) return SpecUpCost.BOOK_COUNT_UNCONFIRMED;
    var price = bookPrice.apply(name).orElse(null);
    if (price == null) return SpecUpCost.BOOK_PRICE_PENDING;
    if (pricesAt[0] == null || price.observedAt().isBefore(pricesAt[0]))
      pricesAt[0] = price.observedAt();
    return SpecUpCost.engravingBooks(price.unitPrice(), levels);
  }

  /**
   * 보석 후보의 종류: 그 칸 효과 문구에 '재사용 대기시간'이 있으면 COOLDOWN, 그 밖 DAMAGE(점수 입력 매퍼와 같은 규칙). 효과를 모르면 이름의 종류(작열
   * → COOLDOWN, 겁화 → DAMAGE), 그것도 모르면 null.
   */
  static String gemType(String kind, List<String> effects) {
    if (effects != null && !effects.isEmpty())
      return String.join(" ", effects).contains("재사용 대기시간") ? "COOLDOWN" : "DAMAGE";
    if ("작열".equals(kind)) return "COOLDOWN";
    if ("겁화".equals(kind)) return "DAMAGE";
    return null;
  }

  /**
   * 재련 +1·상급 재련 +1: 무기·방어구마다. Δ전투력은 시뮬레이터(재련 모델)가 정한다. 일반 재련 비용은 재련 계획의 기대 소모 × 재료 시세 캐시(L30 후속),
   * 상급 재련은 계획 범위 밖이라 UNKNOWN이다.
   */
  private void refines(
      CharacterLoadout loadout,
      java.util.Optional<RefiningMaterialPriceService.Prices> prices,
      Instant[] pricesAt,
      List<Planned> planned) {
    for (int index = 0; index < loadout.equipment().size(); index++) {
      var item = loadout.equipment().get(index);
      if (item == null || !WEAPON_ARMOR.contains(item.type()) || item.detail() == null) continue;
      var refine = item.detail().refineLevel();
      if (refine != null && refine >= 10 && refine < 25)
        planned.add(
            new Planned(
                "EQUIPMENT_REFINE:" + index + ":" + (refine + 1),
                Kind.EQUIPMENT_REFINE.name(),
                item.name(),
                "+" + refine,
                "+" + (refine + 1),
                item.icon(),
                item.grade(),
                null,
                new Change(
                    Kind.EQUIPMENT_REFINE,
                    null,
                    index,
                    null,
                    null,
                    refine + 1,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null),
                refineCost(item, refine, prices, pricesAt)));
      var advanced = item.detail().advancedRefineLevel();
      // 전율 장비에는 상급 재련이 없다(E21). 후보를 만들지 않는다.
      if (advanced != null
          && advanced < 40
          && refineGrade(item.name()) != RefiningRules.Grade.JEONYUL)
        planned.add(
            new Planned(
                "EQUIPMENT_ADVANCED_REFINE:" + index + ":" + (advanced + 1),
                Kind.EQUIPMENT_ADVANCED_REFINE.name(),
                item.name(),
                "상급 재련 " + advanced + "단계",
                "상급 재련 " + (advanced + 1) + "단계",
                item.icon(),
                item.grade(),
                null,
                change(Kind.EQUIPMENT_ADVANCED_REFINE, null, index, advanced + 1, null, null),
                SpecUpCost.REFINE_COST_UNCONFIRMED));
    }
  }

  /**
   * 팔 보호구(완갑) 재련 +1(#575): kind는 EQUIPMENT_REFINE(index = 완갑 장비), 시뮬레이터 입력은 ARM_GUARD(목표 단계, 목표 단계의
   * 등급 — 지금 등급의 최대 단계를 넘으면 승급). 비용은 완갑 재련 규칙(확률·재료)을 아직 넣지 않아 UNKNOWN이다.
   */
  private void armGuard(CharacterLoadout loadout, List<Planned> planned) {
    var item = SpecUpKrakenScore.armGuardItem(loadout);
    if (item == null || item.detail() == null || item.detail().refineLevel() == null) return;
    int refine = item.detail().refineLevel();
    if (refine < 0 || refine >= 25) return;
    var grade = kraken.armGuardGrade(item.grade(), refine + 1);
    if (grade == null) return;
    planned.add(
        new Planned(
            "EQUIPMENT_REFINE:" + loadout.equipment().indexOf(item) + ":" + (refine + 1),
            Kind.EQUIPMENT_REFINE.name(),
            item.name(),
            "+" + refine,
            "+" + (refine + 1) + (grade.equals(item.grade()) ? "" : " " + grade),
            item.icon(),
            item.grade(),
            null,
            new Change(
                Kind.ARM_GUARD,
                null,
                null,
                null,
                refine + 1,
                null,
                null,
                null,
                grade,
                null,
                null,
                null,
                null,
                null),
            SpecUpCost.ARM_GUARD_COST_UNCONFIRMED));
  }

  /**
   * 일반 재련 +1 기대 비용. 계열(업화·전율)은 장비 이름으로 정한다. 숨결 없음과 최대 사용을 모두 계산해 작은 쪽을 고른다. 필요한 재료 시세가 하나라도 없으면
   * PENDING이다.
   */
  SpecUpCost refineCost(
      CharacterLoadout.Equipment item,
      int refine,
      java.util.Optional<RefiningMaterialPriceService.Prices> prices,
      Instant[] pricesAt) {
    var breakdown = refineBreakdown(item, refine, prices);
    if ("OK".equals(breakdown.cost().status())
        && (pricesAt[0] == null || breakdown.prices().observedAt().isBefore(pricesAt[0])))
      pricesAt[0] = breakdown.prices().observedAt();
    return breakdown.cost();
  }

  /**
   * 일반 재련 +1 비용 내역(줄 상세 #544). plan은 비용에 쓴 계획(숨결 없음과 최대 사용 중 기대 비용이 작은 쪽, 시세가 없으면 숨결 없음)이고 계획이 없는
   * 항목이면 null이다. prices는 쓴 재료 시세(없으면 null).
   */
  public record RefineBreakdown(
      RefiningPlan plan,
      boolean breath,
      RefiningMaterialPriceService.Prices prices,
      SpecUpCost cost) {}

  /**
   * 일반 재련 +1 기대 비용. 계열(업화·전율)은 장비 이름으로 정한다. 숨결 없음과 최대 사용을 모두 계산해 작은 쪽을 고른다. 필요한 재료 시세가 하나라도 없으면
   * PENDING이다.
   */
  RefineBreakdown refineBreakdown(
      CharacterLoadout.Equipment item,
      int refine,
      java.util.Optional<RefiningMaterialPriceService.Prices> prices) {
    var grade = refineGrade(item.name());
    if (grade == null)
      return new RefineBreakdown(null, false, null, SpecUpCost.REFINE_COST_UNCONFIRMED);
    var type =
        "무기".equals(item.type())
            ? RefiningRules.EquipmentType.WEAPON
            : RefiningRules.EquipmentType.ARMOR;
    RefiningRules.Track track;
    try {
      track = plans.rules().track(grade, type);
    } catch (IllegalArgumentException exception) {
      return new RefineBreakdown(null, false, null, SpecUpCost.REFINE_COST_UNCONFIRMED);
    }
    int target = refine + 1;
    if (target < track.minTargetStage() || target > track.maxTargetStage())
      return new RefineBreakdown(null, false, null, SpecUpCost.REFINE_COST_UNCONFIRMED);
    var withoutBreath =
        plans.plan(grade, type, new RefiningPlan.Options(refine, target, false, false, true, 0, 0));
    if (prices.isEmpty())
      return new RefineBreakdown(withoutBreath, false, null, SpecUpCost.REFINE_PRICE_PENDING);
    var unitPrices = prices.get();
    RefiningPlan bestPlan = null;
    boolean bestBreath = false;
    Long best = null;
    for (boolean breath : new boolean[] {false, true}) {
      var plan =
          breath
              ? plans.plan(
                  grade, type, new RefiningPlan.Options(refine, target, true, false, true, 0, 0))
              : withoutBreath;
      var total = plan.expectedGold();
      for (var quantity : plan.expectedMaterials()) {
        if (quantity.amount().signum() == 0) continue;
        var unit = unitPrices.unit(quantity.name()).orElse(null);
        if (unit == null)
          return new RefineBreakdown(
              withoutBreath, false, unitPrices, SpecUpCost.REFINE_PRICE_PENDING);
        total = total.add(quantity.amount().multiply(unit));
      }
      long amount = total.setScale(0, RoundingMode.CEILING).longValue();
      if (best == null || amount < best) {
        best = amount;
        bestPlan = plan;
        bestBreath = breath;
      }
    }
    return new RefineBreakdown(bestPlan, bestBreath, unitPrices, SpecUpCost.refineExpected(best));
  }

  /**
   * 등급별 각인 최대 레벨: 유물 4, 영웅·전설 3(크라켄 점수 계수표와 비교 기준 B 표에 전설 4가 없음 — 계수 0으로 Δ가 음수가 되던 줄을 만들지 않는다). 그 밖
   * 등급(고대 등)은 올리는 줄을 만들지 않는다.
   */
  static int engravingMaxLevel(String grade) {
    if ("유물".equals(grade)) return 4;
    if ("영웅".equals(grade) || "전설".equals(grade)) return 3;
    return 0;
  }

  static RefiningRules.Grade refineGrade(String name) {
    if (name == null) return null;
    if (name.contains("전율")) return RefiningRules.Grade.JEONYUL;
    if (name.contains("업화")) return RefiningRules.Grade.UPHWA;
    return null;
  }

  /**
   * 시뮬레이터 입력을 하나로 정할 수 없는 종류. 장신구 연마 교체·깨달음 카르마·어빌리티 스톤은 크라켄 점수를 낼 수 있으면 점수 입력에서 후보를 만들고(Δ크라켄 점수만,
   * #575), 그럴 수 없으면 종류마다 한 줄로 알린다(지원 현황표: 계산식 미확정). 팔찌·아크 그리드·아크 패시브는 한 줄 그대로.
   */
  private void others(
      CharacterLoadout loadout, List<Planned> planned, SpecUpKrakenScore.Context context) {
    boolean bracelet =
        loadout.equipment().stream()
            .anyMatch(
                item ->
                    item != null
                        && "팔찌".equals(item.type())
                        && item.detail() != null
                        && !item.detail().braceletEffects().isEmpty());
    boolean polish =
        loadout.equipment().stream()
            .anyMatch(
                item ->
                    item != null
                        && item.detail() != null
                        && !item.detail().polishEffects().isEmpty());
    if (bracelet) planned.add(Planned.line(Kind.BRACELET_EFFECT, "팔찌 효과"));
    var scored = context.available();
    var polishRows =
        scored ? SpecUpScoreCandidates.polish(context.input(), loadout, polishValues) : null;
    if (polishRows != null) planned.addAll(polishRows);
    else if (polish) planned.add(Planned.line(Kind.POLISH, "장신구 연마 효과"));
    if (loadout.arkGrid() != null && !loadout.arkGrid().slots().isEmpty())
      planned.add(Planned.line(Kind.ARK_GRID_CORE, "아크 그리드 코어"));
    if (loadout.arkPassive() != null && !loadout.arkPassive().points().isEmpty())
      planned.add(Planned.line(Kind.ARK_PASSIVE_POINT, "아크 패시브 포인트"));
    // 앞으로 계산할 종류는 지금 한 줄로만 알린다(PM-백엔드 16:37 ②, kind는 문자열이라 값이 늘어도 깨지지 않음).
    var karmaRows = scored ? SpecUpScoreCandidates.karma(context.input()) : null;
    if (karmaRows != null) planned.addAll(karmaRows);
    else planned.add(Planned.line("KARMA_LEVEL", "카르마 레벨"));
    var stoneRows = scored ? SpecUpScoreCandidates.stone(kraken, context, loadout) : null;
    if (stoneRows != null) planned.addAll(stoneRows);
    else planned.add(Planned.line("ABILITY_STONE", "어빌리티 스톤 교체"));
  }

  private static Change change(
      Kind kind, Integer slot, Integer index, Integer level, Integer refine, Integer quality) {
    return new Change(
        kind, slot, index, null, level, refine, quality, null, null, null, null, null, null, null);
  }

  record Planned(
      String id,
      String kind,
      String target,
      String from,
      String to,
      String icon,
      String grade,
      String skillName,
      Change change,
      SpecUpCost cost,
      Boolean emptySlot,
      BigDecimal baseAttackPctFrom,
      BigDecimal baseAttackPctTo,
      String gemType,
      SpecUpKrakenScore.Edit kraken) {
    Planned(
        String id,
        String kind,
        String target,
        String from,
        String to,
        String icon,
        String grade,
        String skillName,
        Change change,
        SpecUpCost cost,
        Boolean emptySlot,
        BigDecimal baseAttackPctFrom,
        BigDecimal baseAttackPctTo,
        String gemType) {
      this(
          id,
          kind,
          target,
          from,
          to,
          icon,
          grade,
          skillName,
          change,
          cost,
          emptySlot,
          baseAttackPctFrom,
          baseAttackPctTo,
          gemType,
          null);
    }

    Planned(
        String id,
        String kind,
        String target,
        String from,
        String to,
        String icon,
        String grade,
        String skillName,
        Change change,
        SpecUpCost cost,
        Boolean emptySlot,
        BigDecimal baseAttackPctFrom,
        BigDecimal baseAttackPctTo) {
      this(
          id,
          kind,
          target,
          from,
          to,
          icon,
          grade,
          skillName,
          change,
          cost,
          emptySlot,
          baseAttackPctFrom,
          baseAttackPctTo,
          null);
    }

    Planned(
        String id,
        String kind,
        String target,
        String from,
        String to,
        String icon,
        String grade,
        String skillName,
        Change change,
        SpecUpCost cost) {
      this(
          id, kind, target, from, to, icon, grade, skillName, change, cost, null, null, null, null);
    }

    /** 점수 입력에서 만든 후보(시뮬레이터 입력 없음, Δ크라켄 점수만). */
    static Planned scored(
        String id,
        String kind,
        String target,
        String from,
        String to,
        String icon,
        String grade,
        SpecUpCost cost,
        SpecUpKrakenScore.Edit kraken) {
      return new Planned(
          id, kind, target, from, to, icon, grade, null, null, cost, null, null, null, null,
          kraken);
    }

    static Planned line(Kind kind, String target) {
      return line(kind.name(), target);
    }

    static Planned line(String kind, String target) {
      return new Planned(kind, kind, target, null, null, null, null, null, null, null);
    }

    Candidate candidate(
        String status,
        BigDecimal delta,
        BigDecimal percent,
        SpecUpCost cost,
        BigDecimal efficiency,
        CombatPowerReasons.Reason reason,
        String detail) {
      return new Candidate(
          id,
          kind,
          target,
          from,
          to,
          icon,
          grade,
          skillName,
          null,
          change,
          status,
          delta,
          percent,
          cost,
          efficiency,
          reason == null ? null : reason.code(),
          reason == null ? null : reason.text(),
          detail,
          emptySlot,
          baseAttackPctFrom,
          baseAttackPctTo,
          null,
          null,
          gemType);
    }
  }
}
