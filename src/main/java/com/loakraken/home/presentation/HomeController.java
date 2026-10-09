package com.loakraken.home.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.home.application.HomeQueryService;
import com.loakraken.home.application.PatchNoteService;
import com.loakraken.home.domain.NewsFeed;
import com.loakraken.home.domain.PatchNotice;
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
  private final PatchNoteService patchNotes;

  public HomeController(HomeQueryService service, PatchNoteService patchNotes) {
    this.service = service;
    this.patchNotes = patchNotes;
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

  @GetMapping("/patch-notes")
  @Operation(
      summary = "패치 이력",
      description =
          "공식 공지 중 패치 공지(업데이트 내역·밸런스·클라이언트 패치·점검 완료)를 공지 번호로 쌓은 목록입니다(patch-notice-v1). 소식 캐시(10분)를 공유하므로 공식 호출이 늘지 않습니다. "
              + "공식 API는 최근 약 3개월만 주므로 그보다 앞선 이력은 LOAKraken이 관측을 시작한 뒤부터 쌓입니다. 본문은 수집하지 않고 공식 원문 링크를 줍니다. "
              + "503 HOME_CACHE_UNAVAILABLE·HOME_STORAGE_UNAVAILABLE, 400 INVALID_REQUEST")
  public ApiResponse<HomePatchNotesResponse> patchNotes(
      @RequestParam(required = false) PatchNotice.Category category,
      @RequestParam(defaultValue = "1") @Min(1) @Max(100) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(PatchNoteService.MAX_SIZE) int size) {
    // 소식 캐시를 먼저 거치면, 캐시가 비었을 때 새 공지를 받아 쌓는다.
    var news = service.news();
    return ApiResponse.of(
        HomeSuccessCode.HOME_PATCH_NOTES_SUCCESS,
        HomePatchNotesResponse.from(
            patchNotes.list(category, page, size, news.fetchedAt()), category, page, size));
  }
}
