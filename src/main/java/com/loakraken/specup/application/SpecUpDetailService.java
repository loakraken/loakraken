package com.loakraken.specup.application;

import com.loakraken.character.application.CharacterResult;
import com.loakraken.character.application.CombatPowerSimulationService;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CombatPowerGemModel;
import com.loakraken.common.api.BusinessException;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.RefiningMaterialPriceService;
import com.loakraken.market.application.RelicBookPriceService;
import com.loakraken.specup.application.SpecUpService.Candidate;
import com.loakraken.specup.domain.SpecUpCost;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 스펙업 후보 줄 상세(#544). 목록과 같은 캐릭터 캐시로 목록을 다시 만들어 그 후보를 찾고(값이 목록과 같다), 시세 캐시로 사는 품목·재련 재료 내역을 붙인다. 공식
 * API를 부르지 않는다. 근거가 없는 칸은 넣지 않는다.
 */
@Service
public class SpecUpDetailService {
  static final String BOOK_GRADE = RelicBookPriceService.GRADE;

  private final SpecUpService specUp;
  private final CombatPowerSimulationService simulator;
  private final MarketAuctionService auctions;
  private final RelicBookPriceService books;
  private final RefiningMaterialPriceService materials;
  private final MeterRegistry meters;

  public SpecUpDetailService(
      SpecUpService specUp,
      CombatPowerSimulationService simulator,
      MarketAuctionService auctions,
      RelicBookPriceService books,
      RefiningMaterialPriceService materials,
      MeterRegistry meters) {
    this.specUp = specUp;
    this.simulator = simulator;
    this.auctions = auctions;
    this.books = books;
    this.materials = materials;
    this.meters = meters;
  }

  /** 바뀌는 스탯 하나. 근거가 있는 것만 낸다. */
  public record Stat(String name, BigDecimal before, BigDecimal after, String unit) {}

  /** 재련 재료 하나. unitPrice·gold는 시세가 없으면 null(priceStatus PENDING). */
  public record Material(
      String itemName,
      String icon,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal gold,
      String priceStatus,
      String priceBasis) {}

  /**
   * 사거나 파는 품목 하나(보석·각인서). role: BUY(사는 품목) 또는 SELL(지금 보석, 최저가를 비용에서 뺀 경우). price는 1개 즉시 구매 최저가,
   * quantity는 필요한 개수(각인서 5권). SELL만: expectedSalePrice = 최저가 − 판매 수수료, sellFeeGold = 판매 수수료(비용에는 넣지
   * 않음).
   */
  public record Market(
      String role,
      String itemName,
      String icon,
      String grade,
      Long price,
      int quantity,
      Long expectedSalePrice,
      Long sellFeeGold,
      Long listedCount,
      Instant observedAt) {}

  /** 일반 재련 후보만. breath: NONE(숨결 없음) 또는 MAX(최대 사용) — 비용에 쓴 쪽. */
  public record Refine(
      int fromStage,
      int toStage,
      String breath,
      BigDecimal expectedAttempts,
      Integer firstAttemptSuccessRateBp) {}

  public record Detail(
      String characterName,
      Instant fetchedAt,
      Instant pricesAt,
      Candidate candidate,
      BigDecimal combatPowerBefore,
      BigDecimal combatPowerAfter,
      List<Stat> stats,
      List<Material> materials,
      BigDecimal goldFee,
      List<Market> market,
      Refine refine) {}

  public Detail detail(String characterName, String candidateId) {
    return detail(characterName, candidateId, null);
  }

  /**
   * includeCooldownGems가 false면 목록과 같이 작열(쿨) 보석 후보를 빼고 찾는다(목록에서 빠진 후보는 상세도 404). null이면 모든 후보에서
   * 찾는다.
   */
  public Detail detail(String characterName, String candidateId, Boolean includeCooldownGems) {
    if (!specUp.enabled()) throw new BusinessException(SpecUpErrorCode.SPEC_UP_DISABLED);
    var snapshot = simulator.snapshot(characterName);
    var built = build(snapshot, candidateId, includeCooldownGems);
    // 목록을 만든 뒤 내역을 읽는 사이에 시세 캐시가 갱신되면 금액이 어긋난다. 한 번만 다시 만든다.
    if (!built.consistent()) {
      mismatch("retry");
      built = build(snapshot, candidateId, includeCooldownGems);
      // 두 번째도 어긋나면 틀린 금액을 내보내지 않는다(#554). 시세가 바뀌어 목록의 그 후보도 바뀐 것이라 같은 404로 목록을 다시 부르게 한다.
      if (!built.consistent()) {
        mismatch("rejected");
        throw new BusinessException(SpecUpErrorCode.SPEC_UP_CANDIDATE_NOT_FOUND);
      }
    }
    return built.detail();
  }

  private record Built(Detail detail, boolean consistent) {}

  /** 목록 비용과 내역 금액이 어긋난 횟수. outcome: retry(다시 만듦)·rejected(두 번 어긋나 404). */
  private void mismatch(String outcome) {
    meters.counter("loakraken.spec_up.detail_mismatch", "outcome", outcome).increment();
  }

  private Built build(CharacterResult snapshot, String candidateId, Boolean includeCooldownGems) {
    var guide =
        specUp.guide(
            snapshot,
            SpecUpService.Sort.DELTA,
            Integer.MAX_VALUE,
            includeCooldownGems == null ? Boolean.TRUE : includeCooldownGems,
            true);
    var candidate =
        guide.candidates().stream()
            .filter(item -> item.id().equals(candidateId))
            .findFirst()
            .orElseThrow(() -> new BusinessException(SpecUpErrorCode.SPEC_UP_CANDIDATE_NOT_FOUND));
    var before = guide.combatPower();
    var after =
        before == null || candidate.deltaCombatPower() == null
            ? null
            : before.add(candidate.deltaCombatPower());
    var loadout = snapshot.details().loadout();
    var parts = candidateId.split(":");
    List<Stat> stats = List.of();
    List<Material> materialRows = null;
    BigDecimal goldFee = null;
    List<Market> market = null;
    Refine refine = null;
    boolean consistent = true;
    switch (candidate.kind()) {
      case "GEM_LEVEL" -> {
        stats = gemStats(candidate);
        market =
            gemMarket(loadout, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), candidate);
        consistent = matches(candidate.cost(), gemTotal(market));
      }
      case "ENGRAVING_LEVEL" -> {
        market = bookMarket(parts[1], candidate, bookLevels(loadout, parts[1], parts[2]));
        consistent = matches(candidate.cost(), bookTotal(market));
      }
      case "EQUIPMENT_REFINE" -> {
        var index = Integer.parseInt(parts[1]);
        var item = loadout.equipment().get(index);
        int from = item.detail().refineLevel();
        // 팔 보호구(완갑)는 재련 계획 밖이다(비용 UNKNOWN): 단계만 준다.
        if ("완갑".equals(item.type())) {
          refine = new Refine(from, Integer.parseInt(parts[2]), null, null, null);
          break;
        }
        int to = Integer.parseInt(parts[2]);
        var breakdown = specUp.refineBreakdown(item, from, to, materials.cached());
        var plan = breakdown.plan();
        if (plan != null) {
          materialRows = materialRows(plan, breakdown.prices());
          goldFee = plan.expectedGold().setScale(2, RoundingMode.HALF_UP);
          consistent = matches(candidate.cost(), breakdown.cost().amount());
        }
        var stage = plan == null || plan.stages().isEmpty() ? null : plan.stages().getFirst();
        // 여러 단계 줄이면 기대 시도는 단계마다의 합, 첫 시도 확률은 첫 단계(#585).
        BigDecimal attempts = null;
        if (plan != null && !plan.stages().isEmpty()) {
          attempts = BigDecimal.ZERO;
          for (var each : plan.stages()) attempts = attempts.add(each.expectedAttempts());
          attempts = attempts.setScale(2, RoundingMode.HALF_UP);
        }
        refine =
            new Refine(
                from,
                to,
                plan == null ? null : breakdown.breath() ? "OPTIMAL" : "NONE",
                attempts,
                stage == null ? null : stage.firstAttemptSuccessRateBp());
      }
      default -> {}
    }
    return new Built(
        new Detail(
            guide.characterName(),
            guide.fetchedAt(),
            guide.pricesAt(),
            candidate,
            before,
            after,
            stats,
            materialRows,
            goldFee,
            market,
            refine),
        consistent);
  }

  /** 금액이 있는 후보는 내역으로 다시 구한 금액과 같아야 한다. 비용이 OK가 아니면 비교하지 않는다. */
  static boolean matches(SpecUpCost cost, Long total) {
    if (cost == null || !"OK".equals(cost.status())) return true;
    return cost.amount().equals(total);
  }

  /** 보석: 기본 공격력 %(공식 보석 효과 확인값, 목록 후보의 baseAttackPctFrom·To와 같음). */
  static List<Stat> gemStats(Candidate candidate) {
    if (candidate.baseAttackPctFrom() == null || candidate.baseAttackPctTo() == null)
      return List.of();
    return List.of(
        new Stat("기본 공격력", candidate.baseAttackPctFrom(), candidate.baseAttackPctTo(), "%"));
  }

  /**
   * 보석 시세 줄: 목표 레벨 칸(BUY), 비용에서 지금 보석 최저가를 뺀 경우(GEM_UPGRADE) 지금 레벨 칸(SELL). 시세 종류는 목록 비용과 같다(광휘는 그
   * 칸 효과의 종류, 빈 칸은 겁화). 칸을 아직 받지 못했으면 그 줄은 넣지 않는다.
   */
  List<Market> gemMarket(CharacterLoadout loadout, int slot, int next, Candidate candidate) {
    String kind = CombatPowerGemModel.EMPTY_SLOT_KIND;
    int level = 0;
    if (!Boolean.TRUE.equals(candidate.emptySlot()) && loadout.gems() != null) {
      var gem =
          loadout.gems().items().stream()
              .filter(item -> item != null && Integer.valueOf(slot).equals(item.slot()))
              .findFirst()
              .orElse(null);
      if (gem == null || gem.level() == null) return null;
      var matcher = gem.name() == null ? null : SpecUpService.GEM.matcher(gem.name());
      kind =
          SpecUpService.priceKind(
              matcher != null && matcher.find() ? matcher.group(2) : null,
              SpecUpService.gemEffects(loadout).get(slot));
      level = gem.level();
    }
    if (kind == null) return null;
    var cells = new HashMap<String, MarketAuctionService.GemCell>();
    for (var cell : auctions.cachedGems()) cells.put(cell.kind() + ":" + cell.level(), cell);
    var rows = new ArrayList<Market>();
    var buy = cells.get(kind + ":" + next);
    if (buy != null && buy.cell() != null && buy.cell().minBuyPrice() != null)
      rows.add(
          new Market(
              "BUY",
              buy.itemName(),
              null,
              null,
              buy.cell().minBuyPrice(),
              1,
              null,
              null,
              buy.cell().totalCount(),
              buy.cell().observedAt()));
    var cost = candidate.cost();
    if (cost != null && "GEM_UPGRADE".equals(cost.code())) {
      var sell = cells.get(kind + ":" + level);
      if (sell != null && sell.cell() != null && sell.cell().minBuyPrice() != null) {
        long price = sell.cell().minBuyPrice();
        rows.add(
            new Market(
                "SELL",
                sell.itemName(),
                null,
                null,
                price,
                1,
                price - SpecUpCost.exchangeSaleFee(price),
                SpecUpCost.exchangeSaleFee(price),
                sell.cell().totalCount(),
                sell.cell().observedAt()));
      }
    }
    return rows.isEmpty() ? null : List.copyOf(rows);
  }

  /** 시세 줄로 다시 구한 보석 비용(목록 SpecUpCost.gem과 같은 식: 사는 최저가 − 파는 최저가). 사는 줄이 없으면 null. */
  static Long gemTotal(List<Market> market) {
    if (market == null) return null;
    Long buy = null;
    Long sale = null;
    for (var row : market) {
      if ("BUY".equals(row.role())) buy = row.price();
      if ("SELL".equals(row.role())) sale = row.price();
    }
    if (buy == null) return null;
    return sale == null ? buy : Math.max(0, buy - sale);
  }

  /** 유물 각인서 1권 시세 × 5권(목록 비용과 같음). 유물이 아니거나 시세가 없으면 null. */
  /** 올리는 레벨 수(다단계 누적 줄이면 2 이상). 지금 레벨을 모르면 1. */
  static int bookLevels(CharacterLoadout loadout, String engravingName, String next) {
    if (loadout.engravings() == null) return 1;
    for (var engraving : loadout.engravings().arkPassiveEffects())
      if (engraving != null && engravingName.equals(engraving.name()) && engraving.level() != null)
        return Math.max(1, Integer.parseInt(next) - engraving.level());
    return 1;
  }

  List<Market> bookMarket(String engravingName, Candidate candidate) {
    return bookMarket(engravingName, candidate, 1);
  }

  /** levels: 올리는 레벨 수 — 권수 = 5 × levels(목록 비용과 같음). */
  List<Market> bookMarket(String engravingName, Candidate candidate, int levels) {
    if (!BOOK_GRADE.equals(candidate.grade())) return null;
    var price = books.price(engravingName).orElse(null);
    if (price == null) return null;
    return List.of(
        new Market(
            "BUY",
            BOOK_GRADE + " " + engravingName + " 각인서",
            books.icon(engravingName).orElse(null),
            BOOK_GRADE,
            price.unitPrice(),
            SpecUpCost.BOOKS_PER_LEVEL * levels,
            null,
            null,
            null,
            price.observedAt()));
  }

  static Long bookTotal(List<Market> market) {
    if (market == null) return null;
    var row = market.getFirst();
    return row.price() * row.quantity();
  }

  /**
   * 재련 재료 내역: 비용에 쓴 계획의 기대 소모(0개 재료는 뺌) × 재료 1개 즉시 구매 최저가. 수량·단가·금액은 소수 둘째 자리 반올림이고, 목록 비용은 반올림 전
   * 값의 합 + 골드를 올림한 값이다.
   */
  static List<Material> materialRows(
      com.loakraken.refining.domain.RefiningPlan plan, RefiningMaterialPriceService.Prices prices) {
    var rows = new ArrayList<Material>();
    for (var quantity : plan.expectedMaterials()) {
      if (quantity.amount().signum() == 0) continue;
      var unit = prices == null ? null : prices.unit(quantity.name()).orElse(null);
      rows.add(
          new Material(
              quantity.name(),
              null,
              quantity.amount().setScale(2, RoundingMode.HALF_UP),
              unit == null ? null : unit.setScale(2, RoundingMode.HALF_UP),
              unit == null
                  ? null
                  : quantity.amount().multiply(unit).setScale(2, RoundingMode.HALF_UP),
              unit == null ? "PENDING" : "OK",
              unit == null ? null : "MARKET_MIN_UNIT"));
    }
    return List.copyOf(rows);
  }
}
