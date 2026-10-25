package com.loakraken.receipt.application;

import com.loakraken.character.application.CharacterCache;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.CharacterQueryService;
import com.loakraken.character.application.CharacterResult;
import com.loakraken.character.application.CharacterStopList;
import com.loakraken.character.application.SearchCharacterCommand;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterSibling;
import com.loakraken.common.api.BusinessException;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.MaterialUnitPriceService;
import com.loakraken.market.domain.MaterialPriceBasis;
import com.loakraken.receipt.domain.EquipmentReceipt;
import com.loakraken.receipt.domain.GemReceipt;
import com.loakraken.refining.application.RefiningPlanService;
import com.loakraken.refining.domain.OptimalStageCache;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 원정대 영수증(receipt-v1, #173). 같은 서버 원정대의 전투정보로 장비 재련 기대 비용과 거래 가능 보석 시세를 더한다.
 *
 * <p>전투정보는 캐릭터 캐시(5분) → 영수증 입력 보관(receipt.input-ttl, 기본 30분) 순서로 먼저 쓰고, 둘 다 없을 때만 공식 armory를 부른다. 한
 * 요청에 receipt.max-fetch명(기본 2)까지만 새로 부르고 나머지는 PENDING이다. 다시 요청하면 이어서 채운다. 캐릭터를 찾지 못하면 UNAVAILABLE이고
 * 대기 수에 넣지 않는다(다시 요청해도 받을 수 없음). 공식 원문은 저장하지 않는다.
 */
@Service
public class ReceiptService {
  public static final String RULE_VERSION = "receipt-v2";
  private final int maxFetch;
  private final ReceiptInputStore inputs;
  private final CharacterQueryService characters;
  private final CharacterCache cache;
  private final RefiningPlanService refining;
  private final MaterialUnitPriceService materials;
  private final MarketAuctionService auctions;
  private final OptimalStageCache optimalCache;
  private final CharacterStopList stopList;
  private final Clock clock;

  public ReceiptService(
      CharacterQueryService characters,
      CharacterCache cache,
      RefiningPlanService refining,
      MaterialUnitPriceService materials,
      MarketAuctionService auctions,
      OptimalStageCache optimalCache,
      CharacterStopList stopList,
      Clock clock,
      ReceiptInputStore inputs,
      @org.springframework.beans.factory.annotation.Value("${receipt.max-fetch:2}") int maxFetch) {
    if (maxFetch < 1) throw new IllegalArgumentException("receipt.max-fetch는 1 이상이어야 합니다.");
    this.inputs = inputs;
    this.maxFetch = maxFetch;
    this.characters = characters;
    this.cache = cache;
    this.refining = refining;
    this.materials = materials;
    this.auctions = auctions;
    this.optimalCache = optimalCache;
    this.stopList = stopList;
    this.clock = clock;
  }

  /** READY: 계산함. PENDING: 아직 받지 못함(다시 요청하면 채움). UNAVAILABLE: 캐릭터를 찾지 못해 받을 수 없음(대기 수에 넣지 않음). */
  public enum Status {
    READY,
    PENDING,
    UNAVAILABLE
  }

  /**
   * 합계 상태. COMPLETE: 원정대 전원을 받았고 산정 대상을 모두 셌다. PARTIAL: 일부만 셌다(대기 캐릭터, 시세 없는 부위·보석, 읽지 못한 단계) — 합계는
   * 실제보다 작다. UNAVAILABLE: 빠진 것이 있는데 센 금액이 하나도 없다 — 합계 0은 값이 아니다.
   */
  public enum TotalStatus {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE
  }

  public record CharacterReceipt(
      String characterName,
      String className,
      BigDecimal itemLevel,
      Status status,
      EquipmentReceipt.Result equipment,
      Instant observedAt) {}

  public record NotIncluded(String kind, int count) {}

  public record Receipt(
      String serverName,
      String characterName,
      Instant computedAt,
      Instant priceObservedAt,
      BigDecimal equipmentGold,
      List<CharacterReceipt> characters,
      GemReceipt.Result gems,
      List<NotIncluded> notIncluded,
      int rosterCharacters,
      int ready,
      int pending,
      Instant nextAvailableAt,
      boolean assumeBook,
      int unavailable) {
    public BigDecimal totalGold() {
      return equipmentGold.add(gems.gold());
    }

    public TotalStatus totalStatus() {
      boolean missing =
          pending > 0
              || unavailable > 0
              || gems.unpriced() > 0
              || characters.stream()
                  .anyMatch(c -> c.equipment() != null && !c.equipment().complete());
      if (!missing) return TotalStatus.COMPLETE;
      boolean counted =
          gems.priced() > 0
              || characters.stream()
                  .anyMatch(c -> c.equipment() != null && !c.equipment().parts().isEmpty());
      return counted ? TotalStatus.PARTIAL : TotalStatus.UNAVAILABLE;
    }
  }

  public Receipt receipt(String characterName) {
    return receipt(characterName, true);
  }

  /** assumeBook: 책(재봉술·야금술) 효과 적용 가정. API 기본 true(책 포함, 2026-10-11 결정), false면 책 제외. */
  public Receipt receipt(String characterName, boolean assumeBook) {
    var command = new SearchCharacterCommand(characterName);
    var siblings = characters.siblings(command).siblings();
    var self =
        siblings.stream()
            .filter(sibling -> command.characterName().equalsIgnoreCase(sibling.characterName()))
            .findFirst()
            .orElseThrow(() -> new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND));
    String server = self.serverName();
    var listed = listed(siblings, server);
    // 처리정지 목록 캐릭터는 영수증에 넣지 않고 전투정보도 부르지 않는다(PM 2026-10-25). 원정대 수도 뺀 수로 센다.
    var roster =
        siblings.stream()
            .filter(
                sibling ->
                    server.equals(sibling.serverName())
                        && sibling.characterName() != null
                        && !listed.contains(sibling.characterName()))
            .sorted(
                Comparator.comparing(
                        (CharacterSibling sibling) ->
                            sibling.itemLevel() == null ? BigDecimal.ZERO : sibling.itemLevel())
                    .reversed()
                    .thenComparing(CharacterSibling::characterName))
            .toList();

    var loadouts = new HashMap<String, ReceiptInputStore.Input>();
    var unavailableNames = new java.util.HashSet<String>();
    int fetched = 0;
    boolean stopped = false;
    for (var sibling : roster) {
      String name = sibling.characterName();
      var cached = cached(name);
      if (cached.isPresent()) {
        var input = input(cached.get());
        loadouts.put(name, input);
        inputs.save(name, input);
        continue;
      }
      var kept = inputs.find(name);
      if (kept.isPresent()) {
        loadouts.put(name, kept.get());
        continue;
      }
      // 최근에 '없는 캐릭터'로 확인됐으면 다시 부르지 않는다(요청마다 받기 몫을 쓰지 않게).
      if (missing(name)) {
        unavailableNames.add(name);
        continue;
      }
      if (stopped || fetched == maxFetch) continue;
      fetched++;
      try {
        var input = input(characters.search(new SearchCharacterCommand(name, false)));
        loadouts.put(name, input);
        inputs.save(name, input);
      } catch (BusinessException exception) {
        // 없는 캐릭터는 받을 수 없음으로 두고(대기 수에서 뺌), 한도·장애면 나머지를 다음 요청으로 넘긴다.
        if (exception.errorCode() == CharacterErrorCode.CHARACTER_NOT_FOUND)
          unavailableNames.add(name);
        else stopped = true;
      }
    }

    var rules = refining.rules();
    var allEquipment =
        loadouts.values().stream()
            .flatMap(input -> input.loadout().equipment().stream())
            .toList();
    var names = EquipmentReceipt.materialNames(rules, allEquipment);
    var unitPrices = new HashMap<String, BigDecimal>();
    Instant priceObservedAt = null;
    if (!names.isEmpty())
      for (var price : materials.prices(MaterialPriceBasis.CURRENT_MIN_PRICE, List.copyOf(names))) {
        if (price.status() == MaterialUnitPriceService.UnitPrice.Status.PRICED)
          unitPrices.put(price.name(), price.unitPriceGold());
        priceObservedAt = earlier(priceObservedAt, price.observedAt());
      }

    var characterReceipts = new ArrayList<CharacterReceipt>();
    var equipmentGold = BigDecimal.ZERO;
    var gemGroups = new ArrayList<CharacterLoadout.Gems>();
    int arkGridGems = 0;
    int bracelets = 0;
    int accessories = 0;
    // 같은 시세·트랙·단계는 원정대 전체에서 한 번만 계산한다(#284 회차별 최적 계산 비용).
    var stageCache =
        new java.util.HashMap<String, java.util.Optional<EquipmentReceipt.StageCost>>();
    for (var sibling : roster) {
      var input = loadouts.get(sibling.characterName());
      if (input == null) {
        characterReceipts.add(
            new CharacterReceipt(
                sibling.characterName(),
                sibling.className(),
                sibling.itemLevel(),
                unavailableNames.contains(sibling.characterName())
                    ? Status.UNAVAILABLE
                    : Status.PENDING,
                null,
                null));
        continue;
      }
      var loadout = input.loadout();
      var equipment =
          EquipmentReceipt.calculate(
              rules,
              loadout.equipment(),
              unitPrices,
              assumeBook,
              refining.growthCosts(),
              stageCache,
              optimalCache);
      equipmentGold = equipmentGold.add(equipment.gold());
      characterReceipts.add(
          new CharacterReceipt(
              sibling.characterName(),
              sibling.className(),
              sibling.itemLevel(),
              Status.READY,
              equipment,
              input.observedAt()));
      if (loadout.gems() != null) gemGroups.add(loadout.gems());
      if (loadout.arkGrid() != null)
        for (var slot : loadout.arkGrid().slots())
          if (slot != null) arkGridGems += slot.gems().size();
      for (var item : loadout.equipment()) {
        if (item == null || item.type() == null) continue;
        if (item.type().equals("팔찌")) bracelets++;
        if (List.of("목걸이", "귀걸이", "반지").contains(item.type())) accessories++;
      }
    }

    var gemPrices = new HashMap<GemReceipt.Key, GemReceipt.Price>();
    if (!GemReceipt.tradableKeys(gemGroups).isEmpty())
      for (var cell : auctions.gems()) {
        var observed = cell.cell();
        gemPrices.put(
            new GemReceipt.Key(cell.kind(), cell.level()),
            new GemReceipt.Price(
                observed == null || observed.minBuyPrice() == null
                    ? null
                    : BigDecimal.valueOf(observed.minBuyPrice()),
                observed == null));
        if (observed != null) priceObservedAt = earlier(priceObservedAt, observed.observedAt());
      }
    var gems = GemReceipt.calculate(gemGroups, gemPrices);

    int ready = (int) characterReceipts.stream().filter(r -> r.status() == Status.READY).count();
    int pending =
        (int) characterReceipts.stream().filter(r -> r.status() == Status.PENDING).count();
    return new Receipt(
        server,
        self.characterName(),
        clock.instant(),
        priceObservedAt,
        equipmentGold,
        List.copyOf(characterReceipts),
        gems,
        List.of(
            new NotIncluded("ARK_GRID_GEM", arkGridGems),
            new NotIncluded("BRACELET", bracelets),
            new NotIncluded("ACCESSORY", accessories)),
        roster.size(),
        ready,
        pending,
        nextAvailableAt(pending, stopped),
        assumeBook,
        unavailableNames.size());
  }

  /** 영수증에 쓰는 것만(장비·보석·아크 그리드) 남긴 입력. 관측 시각은 그 전투정보를 받은 시각. */
  private static ReceiptInputStore.Input input(CharacterResult result) {
    var loadout = result.details().loadout();
    return new ReceiptInputStore.Input(
        new CharacterLoadout(
            loadout.equipment(), null, loadout.gems(), null, null, loadout.arkGrid(), List.of()),
        result.fetchedAt());
  }

  /**
   * 남은 캐릭터를 이어 받을 수 있는 시각. 남은 사람이 없으면 null. 요청당 상한으로 멈췄으면 지금 바로, 공식 한도·장애로 멈췄으면 1분 뒤(클라이언트 공식
   * 호출 창이 1분이다).
   */
  private Instant nextAvailableAt(int pending, boolean stopped) {
    if (pending == 0) return null;
    var now = clock.instant();
    return stopped ? now.plusSeconds(60) : now;
  }

  public String refiningRuleVersion() {
    return refining.rules().ruleVersion();
  }

  /** 같은 서버 원정대 중 처리정지 목록 이름. 확인하지 못하면 목록 캐릭터가 섞이지 않게 영수증을 내지 않는다(503). */
  private java.util.Set<String> listed(List<CharacterSibling> siblings, String server) {
    var names =
        siblings.stream()
            .filter(sibling -> server.equals(sibling.serverName()))
            .map(CharacterSibling::characterName)
            .filter(java.util.Objects::nonNull)
            .toList();
    try {
      return stopList.listed(names, server);
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE);
    }
  }

  private boolean missing(String name) {
    try {
      return cache.isMissing(name);
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private Optional<CharacterResult> cached(String name) {
    try {
      var armory = cache.findArmory(name);
      return armory.isPresent() ? armory : cache.find(name);
    } catch (BusinessException exception) {
      return Optional.empty();
    }
  }

  private static Instant earlier(Instant current, Instant candidate) {
    if (candidate == null) return current;
    return current == null || candidate.isBefore(current) ? candidate : current;
  }

  public static List<String> assumptions() {
    return assumptions(false);
  }

  /** 가정 문장(계산 검증 6절). 화면에 그대로 보인다. */
  public static List<String> assumptions(boolean assumeBook) {
    return List.of(
        assumeBook
            ? "장비는 업화 +10·전율 +11부터 현재 단계까지 시도(회차)마다 숨결·책 사용 여부를 골라 기대 비용이 가장 작아지는 전략의 비용을 더합니다. 책 효과는 항상 적용된다고 가정합니다."
            : "장비는 업화 +10·전율 +11부터 현재 단계까지 시도(회차)마다 숨결 사용 여부를 골라 기대 비용이 가장 작아지는 전략의 비용을 더합니다. 책(재봉술·야금술)은 게임 문구 '특수 재료 별 효과는 확률에 따라 적용되지 않을 수 있습니다'의 확률이 공개되지 않아 넣지 않습니다.",
        "업화 18단계 이하는 성장 지원을 적용합니다.",
        "장비 성장(경험치) 비용은 단계마다 한 번 드는 운명의 파편을 더합니다(2026-10-10 확인). 업화 목표 11~14단계는 영지 연구 '티어 4 Lv.1590 장비 11~14단계 재련 필요 경험치 20% 감소'를 적용합니다. 실링은 넣지 않습니다.",
        "재료는 현재 최저가 1개당 시세로 환산합니다.",
        "보석은 거래 가능한 것만 경매장 최저 즉시 구매가로 환산하고, 귀속은 개수만 셉니다.");
  }
}
