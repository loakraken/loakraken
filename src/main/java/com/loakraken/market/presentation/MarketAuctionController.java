package com.loakraken.market.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.market.application.GemSkillFilterService;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.domain.AuctionCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping(value = "/api/v1/markets/auctions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(
    name = "경매장 시세",
    description =
        "공식 경매장 T4 보석·장신구 시세 표(auction-board-v1). 칸마다 3분 캐시하고, 빈 칸은 사용자 몫이 남을 때만 채웁니다(PENDING). 전일 대비는 LOAKraken 관측 기준입니다.")
public class MarketAuctionController {
  private final MarketAuctionService auctions;
  private final GemSkillFilterService gemSkills;

  public MarketAuctionController(MarketAuctionService auctions, GemSkillFilterService gemSkills) {
    this.auctions = auctions;
    this.gemSkills = gemSkills;
  }

  @GetMapping("/gems/skills")
  @Operation(
      summary = "보석 매물 직업·스킬 목록",
      description =
          "보석 매물을 거를 직업과 스킬(공식 경매장 검색 옵션 가운데 보석에 붙는 스킬). 공식 호출 없음. 매물 조회의 className·skill에 이 값을 그대로 쓴다. 기능이 꺼져 있으면 404 GEM_FILTER_DISABLED(화면은 필터를 숨김).")
  @StandardErrors
  public ApiResponse<AuctionResponses.AuctionGemSkillsResponse> gemSkills() {
    return ApiResponse.of(
        MarketSuccessCode.AUCTION_GEM_SKILLS_SUCCESS,
        AuctionResponses.AuctionGemSkillsResponse.from(gemSkills.catalog()));
  }

  @GetMapping("/gems")
  @Operation(
      summary = "T4 보석 시세 표",
      description = "겁화·작열 1~10레벨의 즉시 구매 최저가와 매물 수, 전일 대비. 빈 칸은 PENDING이며 잠시 뒤 다시 조회하면 채워집니다.")
  @StandardErrors
  public ApiResponse<AuctionResponses.AuctionGemBoardResponse> gems() {
    return ApiResponse.of(
        MarketSuccessCode.AUCTION_GEMS_SUCCESS,
        AuctionResponses.AuctionGemBoardResponse.from(auctions.gems()));
  }

  @GetMapping("/gems/listings")
  @Operation(
      summary = "T4 보석 매물",
      description =
          "즉시 구매가 오름차순 10개(1분 캐시). 공식 호출 최대 1회. className(직업)·skill(스킬 코드, 직업 필요)로 거를 수 있고 값은 /gems/skills 목록을 쓴다. 목록에 없는 조건은 400 INVALID_GEM_FILTER, 필터가 꺼져 있으면 404 GEM_FILTER_DISABLED. 조건 붙은 검색은 기능 전용 분당 몫(모든 사용자 합)을 넘으면 캐시만 주고 캐시가 없으면 503 MARKET_SEARCH_BUSY + Retry-After, 같은 조건 동시 요청은 공식 검색 하나로 묶는다.")
  @StandardErrors
  public ApiResponse<AuctionResponses.AuctionListingsResponse> gemListings(
      @Parameter(description = "겁화 또는 작열") @RequestParam @Pattern(regexp = "겁화|작열") String kind,
      @Parameter(description = "1~10") @RequestParam @Min(1) @Max(10) int level,
      @Parameter(description = "페이지(1~10)") @RequestParam(required = false) @Min(1) @Max(10)
          Integer page,
      @Parameter(description = "공식 직업 이름(/gems/skills의 className)")
          @RequestParam(required = false)
          @Size(max = 20)
          String className,
      @Parameter(description = "공식 스킬 코드(/gems/skills의 code). className이 필요하다")
          @RequestParam(required = false)
          @Min(1)
          Integer skill) {
    int pageNo = page == null ? 1 : page;
    var listings = gemSkills.listings(kind, level, pageNo, className, skill);
    return ApiResponse.of(
        MarketSuccessCode.AUCTION_LISTINGS_SUCCESS,
        AuctionResponses.AuctionListingsResponse.from(
            listings.page(), pageNo, listings.filter()));
  }

  @GetMapping("/accessories")
  @Operation(
      summary = "T4 장신구 시세 표",
      description =
          "목걸이·귀걸이·반지별 핵심 연마 효과 조합(상상~하중, 서포터 귀걸이는 무공% 상·중·하)의 즉시 구매 최저가와 매물 수, 전일 대비. "
              + "딜러: 적주피·추피 / 무공%·공% / 치피·치적. 서포터: 낙인력·아덴 / 무공% / 아피강·아공강.")
  @StandardErrors
  public ApiResponse<AuctionResponses.AuctionAccessoryBoardResponse> accessories(
      @Parameter(description = "ANCIENT(고대, 기본) 또는 RELIC(유물)") @RequestParam(required = false)
          AuctionCatalog.Grade grade,
      @Parameter(description = "DEALER(기본) 또는 SUPPORT") @RequestParam(required = false)
          AuctionCatalog.Role role,
      @Parameter(description = "최소 품질 0~90(10 단위, 기본 70, 0이면 무관)")
          @RequestParam(required = false)
          @Min(0)
          @Max(90)
          Integer minQuality) {
    var g = grade == null ? AuctionCatalog.Grade.ANCIENT : grade;
    var r = role == null ? AuctionCatalog.Role.DEALER : role;
    var q = quality(minQuality);
    return ApiResponse.of(
        MarketSuccessCode.AUCTION_ACCESSORIES_SUCCESS,
        AuctionResponses.AuctionAccessoryBoardResponse.from(
            g, r, q, auctions.accessories(g, r, q)));
  }

  @GetMapping("/accessories/listings")
  @Operation(
      summary = "T4 장신구 매물",
      description =
          "표의 한 칸에 해당하는 매물. levels는 부위 옵션 순서대로 LOW·MID·HIGH(예: HIGH,MID). 즉시 구매가 오름차순 10개, 1분 캐시.")
  @StandardErrors
  public ApiResponse<AuctionResponses.AuctionListingsResponse> accessoryListings(
      @RequestParam(required = false) AuctionCatalog.Grade grade,
      @RequestParam(required = false) AuctionCatalog.Role role,
      @Parameter(description = "NECKLACE, EARRING, RING") @RequestParam AuctionCatalog.Slot slot,
      @Parameter(description = "옵션 순서대로 LOW·MID·HIGH를 쉼표로") @RequestParam @Size(min = 1, max = 2)
          List<AuctionCatalog.Level> levels,
      @RequestParam(required = false) @Min(0) @Max(90) Integer minQuality,
      @RequestParam(required = false) @Min(1) @Max(10) Integer page) {
    int pageNo = page == null ? 1 : page;
    return ApiResponse.of(
        MarketSuccessCode.AUCTION_LISTINGS_SUCCESS,
        AuctionResponses.AuctionListingsResponse.from(
            auctions.accessoryListings(
                grade == null ? AuctionCatalog.Grade.ANCIENT : grade,
                role == null ? AuctionCatalog.Role.DEALER : role,
                slot,
                levels,
                quality(minQuality),
                pageNo),
            pageNo));
  }

  /** 공식 품질 조건은 10 단위다. 0은 무관(null). */
  private static Integer quality(Integer minQuality) {
    if (minQuality == null) return 70;
    if (minQuality == 0) return null;
    return minQuality / 10 * 10;
  }

  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST 또는 INVALID_AUCTION_LEVELS",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description = "LOST_ARK_RATE_LIMITED(매물 조회)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "502",
        description = "LOST_ARK_API_ERROR(매물 조회)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "LOST_ARK_NOT_CONFIGURED",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "504",
        description = "LOST_ARK_API_TIMEOUT(매물 조회)",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  @interface StandardErrors {}
}
