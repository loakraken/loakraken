package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterCardRefreshService;
import com.loakraken.character.application.CharacterCardService;
import com.loakraken.character.application.CharacterPopularService;
import com.loakraken.character.application.CharacterQueryService;
import com.loakraken.character.application.CharacterViewService;
import com.loakraken.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/characters", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "캐릭터", description = "캐릭터 검색 및 전투정보 조회")
public class CharacterController {
  private final CharacterQueryService queryService;
  private final CharacterViewService views;
  private final CharacterCardService cards;
  private final CharacterPopularService popular;
  private final CharacterCardRefreshService cardRefresh;

  public CharacterController(
      CharacterQueryService queryService,
      CharacterViewService views,
      CharacterCardService cards,
      CharacterPopularService popular,
      CharacterCardRefreshService cardRefresh) {
    this.queryService = queryService;
    this.views = views;
    this.cards = cards;
    this.popular = popular;
    this.cardRefresh = cardRefresh;
  }

  @GetMapping("/search")
  @Operation(
      summary = "캐릭터 검색",
      description =
          "프로필, 장비·보석·각인·카드·아크패시브·아크그리드와 원정대를 제공합니다. 기본 캐시는 5분이며 fetchedAt은 실제 외부 수집 완료 시각입니다. null 섹션은 공식 API에서 데이터가 제공되지 않은 경우입니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "캐릭터 조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "캐릭터명 누락 또는 형식 오류",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_FOUND",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "406",
        description = "NOT_ACCEPTABLE: JSON 이외의 응답 형식 요청",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description = "LOST_ARK_RATE_LIMITED",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        description = "INTERNAL_SERVER_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "502",
        description = "LOST_ARK_API_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description =
            "LOST_ARK_NOT_CONFIGURED, CHARACTER_CACHE_UNAVAILABLE 또는 CHARACTER_STORAGE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "504",
        description = "LOST_ARK_API_TIMEOUT",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CharacterResponse> search(
      @Valid @ModelAttribute @ParameterObject CharacterSearchRequest request,
      HttpServletRequest http) {
    var result = queryService.search(request.toCommand());
    var profile = result.details().profile();
    // 조회수는 검색 화면 응답이 성공했을 때만 센다(F-8). 기록 실패는 응답을 막지 않는다.
    views.record(profile.characterName());
    // 인기 검색(F-10)은 조회수와 따로 하루 1회 중복을 제거해 센다. 원격 주소는 신뢰 프록시를 거친 값이다.
    popular.record(profile.characterName(), profile.serverName(), http.getRemoteAddr());
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_SEARCH_SUCCESS, CharacterResponse.from(result));
  }

  @GetMapping("/popular")
  @Operation(
      summary = "인기 검색 캐릭터",
      description =
          "LOAKraken 캐릭터 검색으로 집계한 인기 캐릭터입니다(popular-v1). 순위 기준: 같은 클라이언트·같은 캐릭터를 KST 하루 1회만 센 고유 조회 수의 기간 합계(24h는 최근 24개 시간 버킷, 7d는 168개)이며, 고유 5회 이상인 캐릭터만 올립니다. "
              + "조회 수 숫자는 공개하지 않습니다. 기간 전체 고유 조회가 30회 미만이면 sampleStatus=COLLECTING이고 목록이 비어 있습니다. 운영 제외 목록에 있는 캐릭터는 빠집니다. 10분 캐시이며 공식 API를 부르지 않습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST: period·limit 오류",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "CHARACTER_POPULAR_DISABLED 또는 CHARACTER_POPULAR_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CharacterPopularResponse> popular(
      @Valid @ModelAttribute @ParameterObject CharacterPopularRequest request) {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_POPULAR_SUCCESS,
        CharacterPopularResponse.from(
            popular.popular(
                CharacterPopularService.Period.of(request.periodOrDefault()),
                request.limitOrDefault())));
  }

  @GetMapping("/views")
  @Operation(
      summary = "캐릭터 조회수",
      description =
          "LOAKraken 캐릭터 검색 성공 횟수를 KST 일별로 집계한 값입니다. 최근 days일(기본 112일=16주)을 0으로 채워 주고 기간·전체 합계를 함께 줍니다. "
              + "외부 호출이 없습니다. 조회한 사람을 식별하지 않으므로 반복 조회도 셉니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "캐릭터명·일수 오류",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "CHARACTER_VIEWS_DISABLED 또는 CHARACTER_VIEWS_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CharacterViewsResponse> views(
      @Valid @ModelAttribute @ParameterObject CharacterViewsRequest request) {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_VIEWS_SUCCESS,
        CharacterViewsResponse.from(views.views(request.characterName(), request.daysOrDefault())));
  }

  @GetMapping("/siblings")
  @Operation(
      summary = "원정대 캐릭터 조회",
      description =
          "공식 원정대 목록만 조회합니다. 캐릭터 검색과 별도로 캐시(기본 5분)하며 같은 이름의 동시 요청을 합칩니다. 공식 API가 빈 목록을 반환하면 존재하지 않는 캐릭터로 처리합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "원정대 조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "캐릭터명 누락 또는 형식 오류",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_FOUND: 공식 API 404 또는 빈 원정대 목록",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "406",
        description = "NOT_ACCEPTABLE: JSON 이외의 응답 형식 요청",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description = "LOST_ARK_RATE_LIMITED",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        description = "INTERNAL_SERVER_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "502",
        description = "LOST_ARK_API_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "LOST_ARK_NOT_CONFIGURED 또는 CHARACTER_CACHE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "504",
        description = "LOST_ARK_API_TIMEOUT",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CharacterSiblingsResponse> siblings(
      @Valid @ModelAttribute @ParameterObject CharacterSiblingsRequest request) {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_SIBLINGS_SUCCESS,
        CharacterSiblingsResponse.from(queryService.siblings(request.toCommand())));
  }

  @GetMapping("/card-collection")
  @Operation(
      summary = "원정대 카드 기록",
      description =
          "원정대 캐릭터가 LOAKraken에서 검색되거나 통계 수집기에 수집될 때 장착한 카드를 기록해, 카드별 최고 각성과 속성 6칸(성·암·뇌·토·수·화) 세트 기록, 보유 카드로 구성할 수 있는 세트를 줍니다. "
              + "공식 API에 카드 도감이 없어 실제 보유의 하한입니다. 원정대 목록은 /siblings 캐시를 공유합니다(공식 호출 최대 1회). 기록이 없어도 200입니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST: 캐릭터명 누락 또는 형식 오류",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_FOUND",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description = "LOST_ARK_RATE_LIMITED",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "502",
        description = "LOST_ARK_API_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description =
            "CARD_COLLECTION_DISABLED, CARD_COLLECTION_UNAVAILABLE, LOST_ARK_NOT_CONFIGURED 또는 CHARACTER_CACHE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "504",
        description = "LOST_ARK_API_TIMEOUT",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CardCollectionResponse> cardCollection(
      @Valid @ModelAttribute @ParameterObject CharacterSiblingsRequest request) {
    var roster = queryService.siblings(request.toCommand());
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_CARD_COLLECTION_SUCCESS,
        CardCollectionResponse.from(cards.collection(request.characterName(), roster.siblings())));
  }

  @PostMapping("/card-collection/refresh")
  @Operation(
      summary = "원정대 카드 기록 채우기",
      description =
          "같은 서버 원정대 중 최근 7일 안에 프로필을 관측하지 못한 캐릭터를 아이템 레벨이 높은 순으로 최대 12명 조회해 카드 기록을 채웁니다. 캐릭터 검색과 같은 경로라 공식 호출은 1회 최대 12회이고, 원정대 목록은 캐시를 씁니다. "
              + "같은 원정대는 10분에 1회만 받으며, 쿨다운 중에는 429와 Retry-After(초)를 줍니다. 카드 기록은 비동기로 저장되므로 응답 직후 card-collection을 다시 조회하면 됩니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "처리 완료(일부 실패 포함)"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST: 캐릭터명 누락 또는 형식 오류",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_FOUND",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description = "CARD_COLLECTION_REFRESH_COOLDOWN(Retry-After 헤더) 또는 LOST_ARK_RATE_LIMITED",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "502",
        description = "LOST_ARK_API_ERROR",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description =
            "CARD_COLLECTION_DISABLED, CARD_COLLECTION_UNAVAILABLE, LOST_ARK_NOT_CONFIGURED 또는 CHARACTER_CACHE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "504",
        description = "LOST_ARK_API_TIMEOUT",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  public ApiResponse<CardCollectionRefreshResponse> refreshCardCollection(
      @Valid @ModelAttribute @ParameterObject CharacterSiblingsRequest request) {
    return ApiResponse.of(
        CharacterSuccessCode.CHARACTER_CARD_COLLECTION_REFRESH_SUCCESS,
        CardCollectionRefreshResponse.from(cardRefresh.refresh(request.characterName())));
  }
}
