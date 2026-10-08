package com.loakraken.home.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.home.application.HomeQueryService;
import com.loakraken.home.domain.NewsFeed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/home", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "홈", description = "공식 이번 주 일정과 공지·이벤트")
@ApiResponses({
  @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      description = "INVALID_REQUEST",
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
      description = "LOST_ARK_NOT_CONFIGURED 또는 HOME_CACHE_UNAVAILABLE",
      content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "504",
      description = "LOST_ARK_API_TIMEOUT",
      content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
})
public class HomeController {
  static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final HomeQueryService service;

  public HomeController(HomeQueryService service) {
    this.service = service;
  }

  @GetMapping("/schedule")
  @Operation(
      summary = "홈 일정",
      description =
          "공식 이번 주 일정(수요일~다음 주 화요일)에서 요청 날짜(KST, 생략 시 오늘)의 모험 섬·필드보스·카오스게이트·기타 일정과 이번 주 골드 섬·주간 표를 줍니다. "
              + "모험 섬 대표 보상은 그 날 회차에 시간 지정으로 붙은 보상만 봅니다. 기본 캐시 30분, 동시 요청 병합, 공유 호출 예산 1회.")
  public ApiResponse<HomeScheduleResponse> schedule(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    var now = LocalDateTime.now(service.clock().withZone(KST));
    return ApiResponse.of(
        HomeSuccessCode.HOME_SCHEDULE_SUCCESS,
        HomeScheduleResponse.from(
            service.calendar(), date == null ? now.toLocalDate() : date, now));
  }

  @GetMapping("/news")
  @Operation(
      summary = "홈 소식",
      description =
          "공식 공지 최신순(limit, 분류 필터)과 진행 중 이벤트 종료 임박순입니다. 제목에 '점검'이 있으면 'N월 N일'을 maintenanceDate로 읽습니다. 기본 캐시 10분, 공유 호출 예산 2회.")
  public ApiResponse<HomeNewsResponse> news(
      @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit,
      @RequestParam(required = false) NewsFeed.Category category) {
    var now = LocalDateTime.now(service.clock().withZone(KST));
    return ApiResponse.of(
        HomeSuccessCode.HOME_NEWS_SUCCESS,
        HomeNewsResponse.from(service.news(), limit, category, now));
  }
}
