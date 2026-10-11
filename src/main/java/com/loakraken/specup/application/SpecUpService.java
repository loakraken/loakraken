package com.loakraken.specup.application;

import com.loakraken.character.application.CharacterResult;
import com.loakraken.character.application.CombatPowerSimulationService;
import com.loakraken.character.application.CombatPowerSimulationService.Change;
import com.loakraken.character.application.CombatPowerSimulationService.Kind;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CombatPowerReasons;
import com.loakraken.common.api.BusinessException;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.RelicBookPriceService;
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
 * 스펙업 가이드(#315, 볼트 「스펙업 가이드 API 설계 초안」 v0 + PM-백엔드·PM-프론트 16:27). 캐릭터 캐시의 현재 세팅에서 후보 업그레이드마다 전투력
 * 시뮬레이터를 <b>변화 하나</b>로 돌려 Δ전투력을 구하고(후보끼리 더하지 않음), 시세 캐시로 비용을 붙여 1만 골드당 전투력으로 정렬한다. 공식 API를 부르지 않는다.
 * 계산할 수 없는 후보도 목록에 이유와 함께 남긴다.
 */
@Service
public class SpecUpService {
  public static final String RULE_VERSION = "spec-up-v1";
  private static final Pattern GEM = Pattern.compile("(\\d+)레벨 (\\S+)의 보석");
  private static final Set<String> WEAPON_ARMOR = Set.of("무기", "투구", "상의", "하의", "장갑", "어깨");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final CombatPowerSimulationService simulator;
  private final MarketAuctionService auctions;
  private final RelicBookPriceService books;
  private final boolean enabled;

  public SpecUpService(
      CombatPowerSimulationService simulator,
      MarketAuctionService auctions,
      RelicBookPriceService books,
      @Value("${spec-up.enabled:false}") boolean enabled) {
    this.simulator = simulator;
    this.auctions = auctions;
    this.books = books;
    this.enabled = enabled;
  }

  public enum Sort {
    EFFICIENCY,
    DELTA
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
      String reasonDetail) {}

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
      List<Candidate> candidates) {}

  public Guide guide(String characterName, Sort sort, int limit) {
    if (!enabled) throw new BusinessException(SpecUpErrorCode.SPEC_UP_DISABLED);
    var snapshot = simulator.snapshot(characterName);
    var profile = snapshot.details().profile();
    var loadout = snapshot.details().loadout();
    var prices = new HashMap<String, MarketAuctionService.GemCell>();
    for (var cell : auctions.cachedGems()) prices.put(cell.kind() + ":" + cell.level(), cell);
    var pricesAt = new Instant[1];
    var planned = new ArrayList<Planned>();
    gems(loadout, prices, pricesAt, planned);
    quality(loadout, planned);
    engravings(loadout, books::price, pricesAt, planned);
    refines(loadout, planned);
    others(loadout, planned);

    var ok = new ArrayList<Candidate>();
    var unsupported = new ArrayList<Candidate>();
    // 딜러가 아니면 모든 후보를 역할 이유로 UNSUPPORTED(PM-백엔드 P0 10-11 20:23).
    var roleReason = CombatPowerSimulationService.roleReason(snapshot);
    for (var plan : planned) {
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
      ("OK".equals(candidate.status()) ? ok : unsupported).add(candidate);
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
        List.copyOf(result));
  }

  /** 효율 순: 효율 있음(내림차순) → 비용 모름(Δ 내림차순). Δ 순: Δ 내림차순. 같으면 id 순. */
  private static Comparator<Candidate> order(Sort sort) {
    Comparator<Candidate> byDelta =
        Comparator.comparing(
            Candidate::deltaCombatPower, Comparator.nullsLast(Comparator.reverseOrder()));
    Comparator<Candidate> tail = Comparator.comparing(Candidate::id);
    if (sort == Sort.DELTA) return byDelta.thenComparing(tail);
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
          "UNSUPPORTED", null, null, null, null, CombatPowerReasons.MODEL_UNCONFIRMED, null);
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
    var cost = plan.cost();
    return plan.candidate(
        "OK", delta, percent, cost, SpecUpCost.efficiency(delta, cost.amount()), null, null);
  }

  /** 보석 +1: 슬롯마다 현재 레벨 + 1(10 미만). 대상 이름은 보석이 붙은 스킬(PM-프론트 16:27 ③). */
  private static void gems(
      CharacterLoadout loadout,
      Map<String, MarketAuctionService.GemCell> prices,
      Instant[] pricesAt,
      List<Planned> planned) {
    if (loadout.gems() == null) return;
    var skills = new HashMap<Integer, String>();
    if (loadout.gems().effects() != null)
      for (var skill : loadout.gems().effects().skills())
        if (skill != null && skill.gemSlot() != null && skill.name() != null)
          skills.putIfAbsent(skill.gemSlot(), skill.name());
    for (var gem : loadout.gems().items()) {
      if (gem == null || gem.slot() == null || gem.level() == null || gem.level() >= 10) continue;
      var matcher = gem.name() == null ? null : GEM.matcher(gem.name());
      String kind = matcher != null && matcher.find() ? matcher.group(2) : null;
      String skill = skills.get(gem.slot());
      String label = gem.level() + "레벨 " + (kind == null ? "보석" : kind);
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
                gemCost(prices, pricesAt, kind, gem.level(), next, bound(gem))));
    }
  }

  private static SpecUpCost gemCost(
      Map<String, MarketAuctionService.GemCell> prices,
      Instant[] pricesAt,
      String kind,
      int level,
      int next,
      boolean bound) {
    if (kind == null || !prices.containsKey(kind + ":" + next)) return SpecUpCost.NO_PRICE_SOURCE;
    var nextCell = prices.get(kind + ":" + next);
    if (nextCell.cell() == null || nextCell.cell().minBuyPrice() == null)
      return SpecUpCost.PRICE_PENDING;
    observed(pricesAt, nextCell);
    Long current = null;
    if (!bound && level >= 1) {
      var currentCell = prices.get(kind + ":" + level);
      if (currentCell == null || currentCell.cell() == null) return SpecUpCost.PRICE_PENDING;
      observed(pricesAt, currentCell);
      current = currentCell.cell().minBuyPrice();
    }
    return SpecUpCost.gem(nextCell.cell().minBuyPrice(), current, bound);
  }

  private static void observed(Instant[] pricesAt, MarketAuctionService.GemCell cell) {
    var at = cell.cell().observedAt();
    if (at != null && (pricesAt[0] == null || at.isBefore(pricesAt[0]))) pricesAt[0] = at;
  }

  /** 귀속 보석은 팔 수 없다(영수증과 같은 판정: 이름의 "(귀속)"). */
  private static boolean bound(CharacterLoadout.Gem gem) {
    return gem.name() != null && gem.name().contains("(귀속)");
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
   * 유물이 아닌 각인은 권수·시세 근거가 없어 UNKNOWN이다.
   */
  private static void engravings(
      CharacterLoadout loadout,
      java.util.function.Function<String, java.util.Optional<RelicBookPriceService.Price>>
          bookPrice,
      Instant[] pricesAt,
      List<Planned> planned) {
    if (loadout.engravings() == null) return;
    for (var engraving : loadout.engravings().arkPassiveEffects()) {
      if (engraving == null || engraving.name() == null || engraving.level() == null) continue;
      if (engraving.level() >= 4) continue;
      int next = engraving.level() + 1;
      planned.add(
          new Planned(
              "ENGRAVING_LEVEL:" + engraving.name() + ":" + next,
              Kind.ENGRAVING_LEVEL.name(),
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
              bookCost(engraving, bookPrice, pricesAt)));
    }
  }

  private static SpecUpCost bookCost(
      CharacterLoadout.PassiveEngraving engraving,
      java.util.function.Function<String, java.util.Optional<RelicBookPriceService.Price>>
          bookPrice,
      Instant[] pricesAt) {
    if (!RelicBookPriceService.GRADE.equals(engraving.grade()))
      return SpecUpCost.BOOK_COUNT_UNCONFIRMED;
    var price = bookPrice.apply(engraving.name()).orElse(null);
    if (price == null) return SpecUpCost.BOOK_PRICE_PENDING;
    if (pricesAt[0] == null || price.observedAt().isBefore(pricesAt[0]))
      pricesAt[0] = price.observedAt();
    return SpecUpCost.engravingBooks(price.unitPrice());
  }

  /** 재련 +1·상급 재련 +1: 무기·방어구마다. 지금은 전투력 계산식이 미확정이라 UNSUPPORTED로 남는다. */
  private static void refines(CharacterLoadout loadout, List<Planned> planned) {
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
                null));
      var advanced = item.detail().advancedRefineLevel();
      if (advanced != null && advanced < 40)
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
                null));
    }
  }

  /** 시뮬레이터 입력을 하나로 정할 수 없는 종류는 종류마다 한 줄로 알린다(지원 현황표: 계산식 미확정). */
  private static void others(CharacterLoadout loadout, List<Planned> planned) {
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
    if (polish) planned.add(Planned.line(Kind.POLISH, "장신구 연마 효과"));
    if (loadout.arkGrid() != null && !loadout.arkGrid().slots().isEmpty())
      planned.add(Planned.line(Kind.ARK_GRID_CORE, "아크 그리드 코어"));
    if (loadout.arkPassive() != null && !loadout.arkPassive().points().isEmpty())
      planned.add(Planned.line(Kind.ARK_PASSIVE_POINT, "아크 패시브 포인트"));
    // 앞으로 계산할 종류는 지금 한 줄로만 알린다(PM-백엔드 16:37 ②, kind는 문자열이라 값이 늘어도 깨지지 않음).
    planned.add(Planned.line("KARMA_LEVEL", "카르마 레벨"));
    planned.add(Planned.line("ABILITY_STONE", "어빌리티 스톤 교체"));
  }

  private static Change change(
      Kind kind, Integer slot, Integer index, Integer level, Integer refine, Integer quality) {
    return new Change(
        kind, slot, index, null, level, refine, quality, null, null, null, null, null, null, null);
  }

  private record Planned(
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
          detail);
    }
  }
}
