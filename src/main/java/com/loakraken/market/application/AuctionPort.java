package com.loakraken.market.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 경매장 칸 캐시·매물 캐시·일별 최저가 기록. 공식 검색은 {@link AuctionSearchPort}다. */
public interface AuctionPort {
  Optional<AuctionCell> findCell(String key);

  void saveCell(String key, AuctionCell cell);

  Optional<AuctionPage> findListings(String key);

  void saveListings(String key, AuctionPage page);

  /** KST 날짜별 마지막 관측 최저가를 남긴다. */
  void recordDaily(String key, LocalDate day, AuctionCell cell);

  /** 각 칸의 전날 마지막 최저가. */
  Map<String, Long> previousDay(List<String> keys, LocalDate day);

  /** 보석 매물 직업·스킬 필터(#542)의 이번 분 기능 몫에서 공식 호출 하나를 가져온다. 넘었거나 저장소 장애면 false(공식 예산 보호). */
  boolean tryAcquireGemFilterQuota(int limit);

  /**
   * @param etcOptions 연마 효과 필터. min·max는 공식 값(×100 정수)
   * @param characterClass 공식 직업 이름(보석 매물 직업 필터). 없으면 null
   * @param skillOptions 공식 SkillOptions(보석 매물 스킬 필터). 없으면 빈 목록
   */
  record AuctionQuery(
      int categoryCode,
      int itemTier,
      String itemGrade,
      String itemName,
      Integer minQuality,
      List<EtcFilter> etcOptions,
      int pageNo,
      String characterClass,
      List<SkillFilter> skillOptions) {
    public AuctionQuery {
      etcOptions = etcOptions == null ? List.of() : List.copyOf(etcOptions);
      skillOptions = skillOptions == null ? List.of() : List.copyOf(skillOptions);
    }

    public AuctionQuery(
        int categoryCode,
        int itemTier,
        String itemGrade,
        String itemName,
        Integer minQuality,
        List<EtcFilter> etcOptions,
        int pageNo) {
      this(
          categoryCode, itemTier, itemGrade, itemName, minQuality, etcOptions, pageNo, null, null);
    }

    /** 직업·스킬 조건이 붙은 검색인가(결과를 시세판 칸에 저장하지 않는다). */
    public boolean filtered() {
      return characterClass != null || !skillOptions.isEmpty();
    }

    /** 캐시·일별 기록 키. 같은 조건이면 같다. */
    public String key() {
      var text = new StringBuilder();
      text.append(categoryCode)
          .append('|')
          .append(itemTier)
          .append('|')
          .append(itemGrade)
          .append('|')
          .append(itemName)
          .append('|')
          .append(minQuality);
      for (var option : etcOptions)
        text.append('|')
            .append(option.first())
            .append(':')
            .append(option.second())
            .append(':')
            .append(option.min())
            .append(':')
            .append(option.max());
      // 조건 없는 검색의 키는 예전과 같다(시세판 칸·일별 기록 키 유지).
      if (characterClass != null) text.append("|class:").append(characterClass);
      for (var option : skillOptions)
        text.append("|skill:").append(option.first()).append(':').append(option.second());
      try {
        var digest =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest, 0, 16);
      } catch (NoSuchAlgorithmException exception) {
        throw new IllegalStateException(exception);
      }
    }

    public AuctionQuery page(int value) {
      return new AuctionQuery(
          categoryCode,
          itemTier,
          itemGrade,
          itemName,
          minQuality,
          etcOptions,
          value,
          characterClass,
          skillOptions);
    }
  }

  record EtcFilter(int first, int second, int min, int max) {}

  /** 공식 SkillOptions 한 줄: first = 스킬 코드, second = 트라이포드 값(보석 종류). 레벨은 아이템 이름으로 거르므로 범위는 비운다. */
  record SkillFilter(int first, int second) {}

  record AuctionPage(long totalCount, List<AuctionListing> items, Instant observedAt) {
    public AuctionPage {
      items = items == null ? List.of() : List.copyOf(items);
    }

    /** 즉시 구매가가 있는 매물 중 최저가. */
    public Long minBuyPrice() {
      return items.stream()
          .map(AuctionListing::buyPrice)
          .filter(price -> price != null && price > 0)
          .min(Long::compare)
          .orElse(null);
    }
  }

  record AuctionListing(
      String name,
      String grade,
      Integer quality,
      Long buyPrice,
      Long bidPrice,
      Long bidStartPrice,
      Instant endDate,
      Integer tradeAllowCount,
      List<ListingOption> options,
      String icon) {
    public AuctionListing {
      options = options == null ? List.of() : List.copyOf(options);
    }

    /** 이미지 없이(공식 Icon을 읽기 전 모양). */
    public AuctionListing(
        String name,
        String grade,
        Integer quality,
        Long buyPrice,
        Long bidPrice,
        Long bidStartPrice,
        Instant endDate,
        Integer tradeAllowCount,
        List<ListingOption> options) {
      this(
          name,
          grade,
          quality,
          buyPrice,
          bidPrice,
          bidStartPrice,
          endDate,
          tradeAllowCount,
          options,
          null);
    }
  }

  /**
   * @param type 공식 Type(ACCESSORY_UPGRADE, STAT, GEM_SKILL_DAMAGE 등)
   * @param className 보석이면 대상 직업
   */
  record ListingOption(
      String type, String name, BigDecimal value, boolean percentage, String className) {}

  record AuctionCell(Long minBuyPrice, long totalCount, Instant observedAt) {}
}
