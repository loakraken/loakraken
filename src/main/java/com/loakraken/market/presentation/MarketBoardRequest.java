package com.loakraken.market.presentation;

import com.loakraken.market.application.MarketBoardService;
import com.loakraken.market.domain.MarketBoardRules;
import com.loakraken.market.domain.MarketTab;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record MarketBoardRequest(
    @NotNull @Schema(description = "보드 탭(GET /api/v1/markets/tabs)", example = "REFINING")
        MarketTab tab,
    @Min(1) @Max(4) @Schema(description = "아이템 티어. 생략하면 탭 기본 티어") Integer tier,
    @Pattern(regexp = "default|high|low|rise|fall|volume")
        @Schema(
            description =
                "default(공식 등급순), high/low(현재 최저가), rise/fall(전일 대비 등락률, 탭 전체 스캔), volume(최근 완료일 7일 거래량 많은 순, 같으면 전일 거래량, 탭 전체 스캔. 거래량을 아직 모으지 못한 품목은 뒤에 공식 등급순)",
            defaultValue = "default")
        String sort,
    @Min(1)
        @Max(MarketBoardService.MAX_SCAN_PAGES)
        @Schema(description = "10개 단위 페이지", defaultValue = "1")
        Integer page,
    @Size(max = 64)
        @Schema(
            description =
                "품목 이름 검색(앞뒤 공백 정리 뒤 1~20자, 한글·자모·영숫자·공백·'·()+-:'). 이름 포함 또는 초성(예: ㅇㅂㄷ) 포함으로 탭 전체 스캔 결과를 거른 뒤 정렬·페이지를 적용합니다. 생략하거나 공백뿐이면 검색하지 않습니다",
            example = "파괴석")
        String q,
    @Pattern(regexp = GRADES)
        @Schema(
            description =
                "공식 아이템 등급으로 거르기(일반·고급·희귀·영웅·전설·유물·고대·에스더). q처럼 탭 전체 스캔 결과를 거른 뒤 정렬·페이지를 적용합니다. 생략하면 모든 등급",
            example = "유물")
        String grade) {
  /** 공식 거래소 아이템 등급 이름. */
  static final String GRADES = "일반|고급|희귀|영웅|전설|유물|고대|에스더";

  static final int QUERY_MAX = 20;
  private static final java.util.regex.Pattern QUERY =
      java.util.regex.Pattern.compile("[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9 ·()+\\-:]+");

  /** 정리한 검색어(공백 여러 개는 하나로). 없거나 공백뿐이면 null. */
  public String query() {
    if (q == null) return null;
    var value = q.strip().replaceAll("\\s+", " ");
    return value.isEmpty() ? null : value;
  }

  @AssertTrue(message = "q는 정리 뒤 1~20자이고 한글·자모·영숫자·공백·'·()+-:'만 받습니다.")
  @Schema(hidden = true)
  public boolean isQueryAllowed() {
    var value = query();
    return value == null
        || (value.codePointCount(0, value.length()) <= QUERY_MAX && QUERY.matcher(value).matches());
  }

  public MarketBoardRules.Sort toSort() {
    return sort == null
        ? MarketBoardRules.Sort.DEFAULT
        : MarketBoardRules.Sort.valueOf(sort.toUpperCase(Locale.ROOT));
  }

  public int pageOrDefault() {
    return page == null ? 1 : page;
  }
}
