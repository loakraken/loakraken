package com.loakraken.receipt.application;

import com.loakraken.character.application.CharacterCache;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.CharacterQueryService;
import com.loakraken.character.application.CharacterResult;
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
 * <p>전투정보는 캐시(5분)를 먼저 쓰고, 없을 때만 공식 armory를 부른다. 한 요청에 {@link #MAX_FETCH}명까지만 새로 부르고 나머지는 PENDING이다.
 * 다시 요청하면 이어서 채운다. 공식 원문은 저장하지 않는다.
 */
@Service
public class ReceiptService {
  public static final String RULE_VERSION = "receipt-v2";
  public static final int MAX_FETCH = 8;
  private final CharacterQueryService characters;
  private final CharacterCache cache;
  private final RefiningPlanService refining;
  private final MaterialUnitPriceService materials;
  private final MarketAuctionService auctions;
  private final Clock clock;

  public ReceiptService(
      CharacterQueryService characters,
      CharacterCache cache,
      RefiningPlanService refining,
      MaterialUnitPriceService materials,
      MarketAuctionService auctions,
      Clock clock) {
    this.characters = characters;
    this.cache = cache;
    this.refining = refining;
    this.materials = materials;
    this.auctions = auctions;
    this.clock = clock;
  }

  public enum Status {
    READY,
    PENDING
  }

  public record CharacterReceipt(
      String characterName,
      String className,
      BigDecimal itemLevel,
      Status status,
      EquipmentReceipt.Result equipment) {}

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
      boolean assumeBook) {
    public BigDecimal totalGold() {
      return equipmentGold.add(gems.gold());
    }
  }

  public Receipt receipt(String characterName) {
    return receipt(characterName, false);
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
    var roster =
        siblings.stream()
            .filter(
                sibling -> server.equals(sibling.serverName()) && sibling.characterName() != null)
            .sorted(
                Comparator.comparing(
                        (CharacterSibling sibling) ->
                            sibling.itemLevel() == null ? BigDecimal.ZERO : sibling.itemLevel())
                    .reversed()
                    .thenComparing(CharacterSibling::characterName))
            .toList();

    var loadouts = new HashMap<String, CharacterLoadout>();
    int fetched = 0;
    boolean stopped = false;
    for (var sibling : roster) {
      var cached = cached(sibling.characterName());
      if (cached.isPresent()) {
        loadouts.put(sibling.characterName(), cached.get().details().loadout());
        continue;
      }
      if (stopped || fetched == MAX_FETCH) continue;
      fetched++;
      try {
        var result = characters.search(new SearchCharacterCommand(sibling.characterName(), false));
        loadouts.put(sibling.characterName(), result.details().loadout());
      } catch (BusinessException exception) {
        // 없는 캐릭터는 건너뛰고, 한도·장애면 나머지를 다음 요청으로 넘긴다.
        if (exception.errorCode() != CharacterErrorCode.CHARACTER_NOT_FOUND) stopped = true;
      }
    }

    var rules = refining.rules();
    var allEquipment =
        loadouts.values().stream().flatMap(loadout -> loadout.equipment().stream()).toList();
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
    for (var sibling : roster) {
      var loadout = loadouts.get(sibling.characterName());
      if (loadout == null) {
        characterReceipts.add(
            new CharacterReceipt(
                sibling.characterName(),
                sibling.className(),
                sibling.itemLevel(),
                Status.PENDING,
                null));
        continue;
      }
      var equipment =
          EquipmentReceipt.calculate(
              rules, loadout.equipment(), unitPrices, assumeBook, refining.growthCosts());
      equipmentGold = equipmentGold.add(equipment.gold());
      characterReceipts.add(
          new CharacterReceipt(
              sibling.characterName(),
              sibling.className(),
              sibling.itemLevel(),
              Status.READY,
              equipment));
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
        roster.size() - ready,
        nextAvailableAt(roster.size() - ready, stopped),
        assumeBook);
  }

  /**
   * 남은 캐릭터를 이어 받을 수 있는 시각. 남은 사람이 없으면 null. 8명 상한으로 멈췄으면 지금 바로, 공식 한도·장애로 멈췄으면 1분 뒤(클라이언트 공식 호출 창이
   * 1분이다).
   */
  private Instant nextAvailableAt(int pending, boolean stopped) {
    if (pending == 0) return null;
    var now = clock.instant();
    return stopped ? now.plusSeconds(60) : now;
  }

  public String refiningRuleVersion() {
    return refining.rules().ruleVersion();
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
            ? "장비는 업화 +10·전율 +11부터 현재 단계까지 단계마다 숨결·책 사용 조합 중 가장 싼 쪽의 기대 비용을 더합니다. 책 효과는 항상 적용된다고 가정합니다."
            : "장비는 업화 +10·전율 +11부터 현재 단계까지 단계마다 숨결 사용 여부 중 더 싼 쪽의 기대 비용을 더합니다. 책(재봉술·야금술)은 게임 문구 '특수 재료 별 효과는 확률에 따라 적용되지 않을 수 있습니다'의 확률이 공개되지 않아 넣지 않습니다.",
        "업화 18단계 이하는 성장 지원을 적용합니다.",
        "장비 성장(경험치) 비용은 단계마다 한 번 드는 운명의 파편을 더합니다(2026-10-10 확인). 업화 목표 11~14단계는 영지 연구 '티어 4 Lv.1590 장비 11~14단계 재련 필요 경험치 20% 감소'를 적용합니다. 실링은 넣지 않습니다.",
        "재료는 현재 최저가 1개당 시세로 환산합니다.",
        "보석은 거래 가능한 것만 경매장 최저 즉시 구매가로 환산하고, 귀속은 개수만 셉니다.");
  }
}
