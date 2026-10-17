package com.loakraken.market.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.market.application.AccessorySearch;
import com.loakraken.market.application.AccessorySearchService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** T4 악세 경매 검색기(N13) 응답. 판매자 정보는 공식 응답에 없고 다루지 않는다. */
public final class AccessorySearchResponses {
  private AccessorySearchResponses() {}

  public record AccessorySearchResponse(
      @Schema(description = "계산 규칙 버전", example = AccessorySearchService.RULE_VERSION)
          String ruleVersion,
      @Schema(description = "공식 TotalCount(서버 필터 기준 매물 수, 뒤 필터 전)") long totalCount,
      @Schema(description = "요청한 공식 시작 페이지") int page,
      @Schema(description = "공식 조회 시각(캐시면 처음 조회한 시각)") Instant observedAt,
      @Schema(description = "캐시에서 준 결과면 true") boolean cached,
      @Schema(
              description =
                  "힘민지 범위 % 조건이 없거나, 결과가 10개 이상 찼거나, 공식 매물을 끝까지 읽었으면 true. false면 scannedPages 안에서 찾은 결과입니다")
          boolean complete,
      @Schema(description = "이번에 읽은 공식 페이지 수(뒤 필터면 최대 3)") int scannedPages,
      @NullableResponse @Schema(description = "이어서 읽을 공식 페이지(page로 다시 요청). 더 없으면 null")
          Integer nextPage,
      @Schema(description = "즉시 구매가 오름차순") List<AccessorySearchItemResponse> items) {
    static AccessorySearchResponse from(AccessorySearch.Result result) {
      return new AccessorySearchResponse(
          AccessorySearchService.RULE_VERSION,
          result.totalCount(),
          result.page(),
          result.observedAt(),
          result.cached(),
          result.complete(),
          result.scannedPages(),
          result.nextPage(),
          result.items().stream().map(AccessorySearchItemResponse::from).toList());
    }
  }

  public record AccessorySearchItemResponse(
      String name,
      @NullableResponse String grade,
      @NullableResponse Integer quality,
      @Schema(description = "연마 효과 줄(공식 순서)") List<AccessorySearchPolishResponse> polish,
      @Schema(description = "연마 단계 = 연마 효과 줄 수") int polishStage,
      @NullableResponse @Schema(description = "깨달음(아크 패시브 포인트)") Integer enlightenment,
      @NullableResponse @Schema(description = "힘·민첩·지능 기본 효과") Integer mainStat,
      @NullableResponse
          @Schema(
              description =
                  "힘민지 범위 % = (힘민지 − 그 연마 단계 최솟값) ÷ (최댓값 − 최솟값) × 100, 소수 둘째 자리. 범위표는 공식 아이템 사전. 연마 단계가 0~3 밖이거나 힘민지가 없으면 null")
          BigDecimal mainStatRangePct,
      @NullableResponse @Schema(description = "구매 후 거래 가능 횟수(0이면 거래 불가)") Integer tradeAllowCount,
      @NullableResponse @Schema(description = "즉시 구매가. 입찰만 가능한 매물이면 null(0은 주지 않음)") Long buyPrice,
      @NullableResponse Long bidStartPrice,
      @NullableResponse @Schema(description = "경매 종료 시각") Instant endDate) {
    static AccessorySearchItemResponse from(AccessorySearch.Item item) {
      return new AccessorySearchItemResponse(
          item.name(),
          item.grade(),
          item.quality(),
          item.polish().stream().map(AccessorySearchPolishResponse::from).toList(),
          item.polishStage(),
          item.enlightenment(),
          item.mainStat(),
          item.mainStatRangePct(),
          item.tradeAllowCount(),
          item.buyPrice(),
          item.bidStartPrice(),
          item.endDate());
    }
  }

  public record AccessorySearchPolishResponse(
      @Schema(description = "공식 연마 효과 이름") String name,
      @NullableResponse
          @Schema(description = "검색 조건에 쓰는 핵심 연마 효과면 그 코드(PolishOption 이름), 아니면 null(최대 생명력 등)")
          String option,
      @NullableResponse @Schema(description = "줄임 이름(예: 적주피). option이 null이면 null")
          String shortName,
      @NullableResponse BigDecimal value,
      boolean percentage,
      @NullableResponse
          @Schema(
              description = "T4 하·중·상 값이면 LOW·MID·HIGH, 아니면 null",
              allowableValues = {"LOW", "MID", "HIGH"})
          String level) {
    static AccessorySearchPolishResponse from(AccessorySearch.Polish polish) {
      return new AccessorySearchPolishResponse(
          polish.name(),
          polish.option() == null ? null : polish.option().name(),
          polish.option() == null ? null : polish.option().shortName(),
          polish.value(),
          polish.percentage(),
          polish.level() == null ? null : polish.level().name());
    }
  }
}
