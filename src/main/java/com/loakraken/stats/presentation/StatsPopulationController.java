package com.loakraken.stats.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import com.loakraken.common.api.ResponseContracts;
import com.loakraken.stats.application.StatsPopulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 전체 인구 탭(통계 묶음 B5). 주간 스냅샷만 읽는다(공식 API·집계 쿼리 없음). */
@RestController
@Validated
@Tag(name = "통계·랭킹")
public class StatsPopulationController {
  private final StatsPopulationService population;

  public StatsPopulationController(StatsPopulationService population) {
    this.population = population;
  }

  @GetMapping(value = "/api/v1/stats/population", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "전체 인구",
      description =
          "LOAKraken이 확인한 모든 캐릭터(조건 없음, 처리정지 목록 제외)의 서버별·직업별·레벨별 수. 주마다 만드는 스냅샷이며 snapshotDate가 집계 기준일입니다. "
              + "1640 미만은 원정대 목록에서만 저장한 캐릭터라 레벨별 수에만 의미가 있습니다. 스냅샷이 아직 없으면 snapshotDate null·빈 목록입니다. "
              + "400 INVALID_REQUEST, 503 STATS_UNAVAILABLE")
  @StatsController.StandardErrors
  public ApiResponse<PopulationResponse> population(
      @Parameter(description = "서버 이름. 주면 그 서버의 직업·레벨 분포(서버 목록은 늘 전체)")
          @RequestParam(required = false)
          @Size(max = 16)
          @Pattern(regexp = "[\\p{L}\\p{N}]+")
          String server) {
    var result = population.population(server);
    return ApiResponse.of(
        StatsSuccessCode.STATS_POPULATION_SUCCESS,
        new PopulationResponse(
            result.snapshotDate(),
            result.server(),
            result.total(),
            result.servers().stream().map(c -> new CountResponse(c.name(), c.count())).toList(),
            result.classes().stream().map(c -> new CountResponse(c.name(), c.count())).toList(),
            result.levels().stream()
                .map(level -> new LevelCountResponse(level.from(), level.to(), level.count()))
                .toList()));
  }

  public record PopulationResponse(
      @Schema(description = "집계 기준일(KST, 스냅샷 날짜). 아직 스냅샷이 없으면 null") @NullableResponse
          LocalDate snapshotDate,
      @Schema(description = "요청한 서버(없으면 null = 전체)") @NullableResponse String server,
      @Schema(description = "그 범위(전체 또는 서버)의 캐릭터 수 = levels count의 합") long total,
      @Schema(description = "서버별 수(늘 전체, 많은 순)") List<CountResponse> servers,
      @Schema(description = "직업별 수(그 범위, 많은 순)") List<CountResponse> classes,
      @Schema(description = "아이템 레벨 10 단위 수(그 범위, 오름차순)") List<LevelCountResponse> levels) {}

  public record CountResponse(String name, long count) {}

  public record LevelCountResponse(
      @Schema(description = "아이템 레벨 하한(포함)") int from,
      @Schema(description = "아이템 레벨 상한(미포함)") int to,
      long count) {}

  @Configuration(proxyBeanMethods = false)
  static class StatsPopulationOpenApiConfiguration {
    @Bean
    OpenApiCustomizer statsPopulationResponseContract() {
      return document -> {
        var schemas = document.getComponents().getSchemas();
        ResponseContracts.apply(PopulationResponse.class, schemas);
        ResponseContracts.apply(CountResponse.class, schemas);
        ResponseContracts.apply(LevelCountResponse.class, schemas);
      };
    }
  }
}
