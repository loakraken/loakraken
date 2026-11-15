package com.loakraken.market.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.market.application.AuctionPort;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.domain.AuctionCatalog;
import com.loakraken.market.domain.GemSkillCatalog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 경매장 시세 표 응답(auction-board-v1). 가격 단위는 골드다. */
public final class AuctionResponses {
  private AuctionResponses() {}

  @Schema(
      description =
          "경매장 칸 하나의 기간 시세(LOAKraken 관측). 값은 그날 마지막으로 관측한 즉시 구매 최저가(공식 일평균 아님)이고, 누군가 그 칸을 본 날만 행이 있습니다.")
  public record AuctionCellHistoryResponse(
      @Schema(example = "auction-board-v1") String ruleVersion,
      @Schema(description = "적용한 기간(7d·14d·30d·90d·365d)", example = "30d") String range,
      @NullableResponse @Schema(description = "이 칸을 처음 저장한 날짜(기간과 상관없이). 없으면 null")
          LocalDate recordedSince,
      @Schema(description = "기간 안 저장 일수") int storedDays,
      @Schema(description = "최신순. 저장이 없는 날은 행 없음") List<AuctionCellDayResponse> days) {
    static AuctionCellHistoryResponse from(
        com.loakraken.market.application.MarketHistoryRangeService.CellRange ranged) {
      var days =
          ranged.stored().days().stream()
              .map(
                  day ->
                      new AuctionCellDayResponse(
                          day.date(),
                          day.minBuyPrice(),
                          day.totalCount(),
                          day.observedAt(),
                          !day.date().isBefore(ranged.today())))
              .toList();
      return new AuctionCellHistoryResponse(
          AuctionCatalog.RULE_VERSION,
          ranged.range().value(),
          ranged.stored().firstDate(),
          days.size(),
          days);
    }
  }

  public record AuctionCellDayResponse(
      LocalDate date,
      @Schema(description = "그날 마지막 관측 즉시 구매 최저가") long minBuyPrice,
      @Schema(description = "그 관측의 매물 수(공식 TotalCount)") long totalCount,
      @Schema(description = "그 관측 시각(UTC)") Instant observedAt,
      @Schema(description = "오늘(한국 날짜)이면 true: 하루가 끝나지 않아 값이 바뀔 수 있음") boolean partial) {}

  public record AuctionGemBoardResponse(
      @Schema(example = "auction-board-v1") String ruleVersion,
      @Schema(description = "PENDING 칸이 있으면 false. 잠시 뒤 다시 조회하면 채워집니다") boolean complete,
      @Schema(description = "겁화 10→1레벨, 작열 10→1레벨 순") List<AuctionGemCellResponse> cells) {
    static AuctionGemBoardResponse from(List<MarketAuctionService.GemCell> cells) {
      return new AuctionGemBoardResponse(
          AuctionCatalog.RULE_VERSION,
          cells.stream().allMatch(cell -> cell.status() == MarketAuctionService.Status.READY),
          cells.stream()
              .map(
                  cell ->
                      new AuctionGemCellResponse(
                          cell.kind(),
                          cell.level(),
                          cell.itemName(),
                          cell.cell() == null ? null : cell.cell().minBuyPrice(),
                          cell.cell() == null ? null : cell.cell().totalCount(),
                          cell.previousDayChangeRate(),
                          cell.cell() == null ? null : cell.cell().observedAt(),
                          cell.status().name(),
                          cell.stale()))
              .toList());
    }
  }

  public record AuctionGemCellResponse(
      @Schema(allowableValues = {"겁화", "작열"}) String kind,
      int level,
      @Schema(example = "10레벨 겁화의 보석") String itemName,
      @NullableResponse @Schema(description = "즉시 구매 최저가. 즉시 구매 매물이 없거나 PENDING이면 null")
          Long minBuyPrice,
      @NullableResponse @Schema(description = "매물 수(공식 TotalCount)") Long totalCount,
      @NullableResponse @Schema(description = "전날 마지막 관측 최저가 대비(%). 우리 관측 기준")
          BigDecimal previousDayChangeRate,
      @NullableResponse Instant observedAt,
      @Schema(allowableValues = {"READY", "PENDING"}) String status,
      @Schema(
              description =
                  "true면 최근 3분 안의 값이 아직 없어 마지막으로 관측한 값을 보입니다(observedAt이 그 시각, status는 READY). 다음 조회에서 새 값으로 바뀝니다")
          boolean stale) {}

  public record AuctionAccessoryBoardResponse(
      @Schema(example = "auction-board-v1") String ruleVersion,
      @Schema(allowableValues = {"ANCIENT", "RELIC"}) String grade,
      @Schema(allowableValues = {"DEALER", "SUPPORT"}) String role,
      @NullableResponse @Schema(description = "최소 품질. null이면 품질 무관") Integer minQuality,
      boolean complete,
      List<AuctionAccessorySlotResponse> slots) {
    static AuctionAccessoryBoardResponse from(
        AuctionCatalog.Grade grade,
        AuctionCatalog.Role role,
        Integer minQuality,
        List<MarketAuctionService.AccessorySlot> slots) {
      return new AuctionAccessoryBoardResponse(
          AuctionCatalog.RULE_VERSION,
          grade.name(),
          role.name(),
          minQuality,
          slots.stream()
              .flatMap(slot -> slot.cells().stream())
              .allMatch(cell -> cell.status() == MarketAuctionService.Status.READY),
          slots.stream().map(AuctionAccessorySlotResponse::from).toList());
    }
  }

  public record AuctionAccessorySlotResponse(
      @Schema(allowableValues = {"NECKLACE", "EARRING", "RING"}) String slot,
      @Schema(example = "목걸이") String slotName,
      List<AuctionPolishOptionResponse> options,
      @Schema(description = "옵션 2개면 상상·상중·중상·중중·상하·하상, 1개면 상·중·하")
          List<AuctionAccessoryCellResponse> cells) {
    static AuctionAccessorySlotResponse from(MarketAuctionService.AccessorySlot slot) {
      return new AuctionAccessorySlotResponse(
          slot.slot().name(),
          slot.slot().label(),
          slot.options().stream().map(AuctionPolishOptionResponse::from).toList(),
          slot.cells().stream()
              .map(
                  cell ->
                      new AuctionAccessoryCellResponse(
                          cell.levels().stream().map(Enum::name).toList(),
                          cell.label(),
                          cell.optionLabels(),
                          cell.cell() == null ? null : cell.cell().minBuyPrice(),
                          cell.cell() == null ? null : cell.cell().totalCount(),
                          cell.previousDayChangeRate(),
                          cell.cell() == null ? null : cell.cell().observedAt(),
                          cell.status().name()))
              .toList());
    }
  }

  public record AuctionPolishOptionResponse(
      @Schema(example = "ENEMY_DAMAGE") String key,
      @Schema(example = "적에게 주는 피해 증가") String officialName,
      @Schema(example = "적주피") String shortName,
      @Schema(description = "하·중·상 값(%, 공식 표시값)") List<BigDecimal> values) {
    static AuctionPolishOptionResponse from(AuctionCatalog.PolishOption option) {
      return new AuctionPolishOptionResponse(
          option.name(),
          option.officialName(),
          option.shortName(),
          List.of(
              percent(option.value(AuctionCatalog.Level.LOW)),
              percent(option.value(AuctionCatalog.Level.MID)),
              percent(option.value(AuctionCatalog.Level.HIGH))));
    }
  }

  public record AuctionAccessoryCellResponse(
      @Schema(description = "옵션 순서대로 LOW·MID·HIGH") List<String> levels,
      @Schema(example = "상중") String label,
      @Schema(example = "[\"적주피 상\", \"추피 중\"]") List<String> optionLabels,
      @NullableResponse Long minBuyPrice,
      @NullableResponse Long totalCount,
      @NullableResponse BigDecimal previousDayChangeRate,
      @NullableResponse Instant observedAt,
      @Schema(allowableValues = {"READY", "PENDING"}) String status) {}

  public record AuctionListingsResponse(
      @Schema(description = "공식 TotalCount") long totalCount,
      int page,
      Instant observedAt,
      @Schema(description = "즉시 구매가 오름차순, 페이지당 10개") List<AuctionListingResponse> items,
      @Schema(description = "보석 매물을 직업·스킬로 거른 조건. 조건 없는 검색·장신구 매물은 null") @NullableResponse
          AuctionGemFilterResponse filter) {
    static AuctionListingsResponse from(AuctionPort.AuctionPage page, int pageNo) {
      return from(page, pageNo, null);
    }

    static AuctionListingsResponse from(
        AuctionPort.AuctionPage page, int pageNo, GemSkillCatalog.Filter filter) {
      return new AuctionListingsResponse(
          page.totalCount(),
          pageNo,
          page.observedAt(),
          page.items().stream().map(AuctionListingResponse::from).toList(),
          AuctionGemFilterResponse.from(filter));
    }
  }

  public record AuctionGemFilterResponse(
      @Schema(description = "공식 직업 이름", example = "블래스터") String className,
      @Schema(description = "공식 스킬 코드. 직업만 거르면 null", example = "30210") @NullableResponse
          Integer skill,
      @Schema(description = "공식 스킬 이름. 직업만 거르면 null", example = "플라즈마 스톰") @NullableResponse
          String skillName) {
    static AuctionGemFilterResponse from(GemSkillCatalog.Filter filter) {
      if (filter == null) return null;
      return new AuctionGemFilterResponse(
          filter.className(),
          filter.skill() == null ? null : filter.skill().code(),
          filter.skill() == null ? null : filter.skill().name());
    }
  }

  public record AuctionGemSkillsResponse(
      @Schema(description = "목록을 공식 경매장 검색 옵션과 맞춘 날짜(KST)", example = "2026-10-18")
          String checkedOn,
      @Schema(description = "공식 직업 순서 그대로") List<AuctionGemClassResponse> classes) {
    static AuctionGemSkillsResponse from(GemSkillCatalog catalog) {
      return new AuctionGemSkillsResponse(
          catalog.checkedOn(),
          catalog.classes().stream().map(AuctionGemClassResponse::from).toList());
    }
  }

  public record AuctionGemClassResponse(
      @Schema(example = "블래스터") String className, List<AuctionGemSkillResponse> skills) {
    static AuctionGemClassResponse from(GemSkillCatalog.GemClass value) {
      return new AuctionGemClassResponse(
          value.className(), value.skills().stream().map(AuctionGemSkillResponse::from).toList());
    }
  }

  public record AuctionGemSkillResponse(
      @Schema(description = "공식 스킬 코드(매물 조회 skill에 그대로)", example = "30210") int code,
      @Schema(description = "공식 스킬 이름", example = "플라즈마 스톰") String name,
      @Schema(description = "이 스킬로 거를 수 있는 보석 종류(겁화·작열, 문자열)") List<String> kinds) {
    static AuctionGemSkillResponse from(GemSkillCatalog.GemSkill value) {
      return new AuctionGemSkillResponse(value.code(), value.name(), value.kinds());
    }
  }

  public record AuctionListingResponse(
      String name,
      @NullableResponse String grade,
      @NullableResponse @Schema(description = "품질. 보석은 null") Integer quality,
      @NullableResponse @Schema(description = "즉시 구매가. 입찰만 가능한 매물이면 null 또는 0") Long buyPrice,
      @NullableResponse @Schema(description = "현재 입찰가(입찰이 없으면 0)") Long bidPrice,
      @NullableResponse Long bidStartPrice,
      @NullableResponse @Schema(description = "경매 종료 시각") Instant endDate,
      @NullableResponse @Schema(description = "구매 후 거래 가능 횟수(0이면 거래 불가)") Integer tradeAllowCount,
      List<AuctionListingOptionResponse> options,
      @NullableResponse @Schema(description = "아이템 이미지 URL(공식 Icon 그대로). 공식 응답에 없으면 null")
          String icon) {
    static AuctionListingResponse from(AuctionPort.AuctionListing listing) {
      return new AuctionListingResponse(
          listing.name(),
          listing.grade(),
          listing.quality(),
          listing.buyPrice(),
          listing.bidPrice(),
          listing.bidStartPrice(),
          listing.endDate(),
          listing.tradeAllowCount(),
          listing.options().stream().map(AuctionListingOptionResponse::from).toList(),
          listing.icon());
    }
  }

  public record AuctionListingOptionResponse(
      @Schema(description = "공식 Type(ACCESSORY_UPGRADE, STAT, GEM_SKILL_DAMAGE 등)") String type,
      String name,
      @NullableResponse BigDecimal value,
      boolean percentage,
      @NullableResponse
          @Schema(
              description = "T4 연마 효과면 LOW·MID·HIGH, 아니면 null",
              allowableValues = {"LOW", "MID", "HIGH"})
          String level,
      @NullableResponse @Schema(description = "보석 대상 직업") String className) {
    static AuctionListingOptionResponse from(AuctionPort.ListingOption option) {
      var level =
          "ACCESSORY_UPGRADE".equals(option.type())
              ? AuctionCatalog.PolishOption.byOfficialName(option.name())
                  .flatMap(polish -> polish.levelOf(option.value()))
                  .map(Enum::name)
                  .orElse(null)
              : null;
      return new AuctionListingOptionResponse(
          option.type(),
          option.name(),
          option.value(),
          option.percentage(),
          level,
          option.className());
    }
  }

  private static BigDecimal percent(int raw) {
    return BigDecimal.valueOf(raw, 2);
  }
}
