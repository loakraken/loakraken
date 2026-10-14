package com.loakraken.stats.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import com.loakraken.common.api.ResponseContracts;
import com.loakraken.stats.application.StatsQueryService;
import com.loakraken.stats.domain.ClassBuilds;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springdoc.core.annotations.ParameterObject;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 직업 상세 빌드 통계(M12 1단계). */
@RestController
@Validated
@Tag(name = "통계·랭킹")
public class ClassBuildsController {
  private final StatsQueryService stats;
  private final com.loakraken.stats.application.ClassBuildCellsService characterCells;

  public ClassBuildsController(
      StatsQueryService stats,
      com.loakraken.stats.application.ClassBuildCellsService characterCells) {
    this.stats = stats;
    this.characterCells = characterCells;
  }

  @GetMapping(
      value = "/api/v1/stats/classes/{className}/builds/character",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "직업 빌드 통계에서 캐릭터 한 명의 칸",
      description =
          "캐릭터 한 명이 직업 빌드 통계(class-builds-v1)의 어느 칸에 드는지 줍니다. 집계와 같은 규칙이며, cells의 항목 이름은 빌드 통계 응답의 항목, 값은 그 항목 cells[]의 key와 같은 모양입니다. "
              + "공식 API를 부르지 않습니다: 최근 검색 캐시 → 저장된 마지막 장비 요약 순서로 찾고, 둘 다 없으면 404 CHARACTER_NOT_CACHED입니다. "
              + "inSample은 그 캐릭터 값이 이 조건(직업·아이템 레벨 범위·최근 확인 기간·전투력 관측)에 맞는지이며, 실제로 집계에 들어갔는지는 뜻하지 않습니다. "
              + "itemLevelMin·braceletItemLevelMin 기본값은 빌드 통계와 같습니다. "
              + "400 INVALID_REQUEST, 404 CHARACTER_NOT_CACHED, 503 CHARACTER_STORAGE_UNAVAILABLE")
  @StatsController.StandardErrors
  public ApiResponse<ClassBuildCellsResponse> character(
      @Parameter(description = "직업 이름(공식 표기)")
          @PathVariable
          @Size(min = 1, max = 16)
          @Pattern(regexp = "[\\p{L}]+")
          String className,
      @Valid @ModelAttribute @ParameterObject ClassBuildCellsRequest request) {
    return ApiResponse.of(
        StatsSuccessCode.STATS_CLASS_BUILDS_SUCCESS,
        ClassBuildCellsResponse.from(
            characterCells.cells(
                className,
                request.characterName(),
                request.itemLevelMin(),
                request.itemLevelMax(),
                request.activeDays(),
                request.braceletItemLevelMin())));
  }

  @Schema(
      description =
          "캐릭터 한 명의 칸. cells의 항목 이름 = 빌드 통계 응답 항목, 값 = 그 항목 cells[] key와 같은 모양. 값이 null이면 그 항목을 못 읽어 분모에서 빠지는 캐릭터")
  public record ClassBuildCellsResponse(
      String characterName,
      String serverName,
      @Schema(description = "캐릭터의 직업(공식 표기)") String className,
      BigDecimal itemLevel,
      @Schema(description = "값을 읽은 관측 시각(캐시면 검색 시각, 저장이면 마지막 프로필 확인 시각)") @NullableResponse
          java.time.Instant observedAt,
      @Schema(
              description = "LIVE_CACHE: 최근 검색 캐시, STORED_OBSERVATION: 저장된 마지막 장비 요약",
              allowableValues = {"LIVE_CACHE", "STORED_OBSERVATION"})
          String source,
      @Schema(description = "이 캐릭터 값이 조건(직업·아이템 레벨·최근 확인 기간·전투력 관측)에 맞는가. 실제 집계 포함 여부는 뜻하지 않음")
          boolean inSample,
      @Schema(
              description =
                  "inSample=false인 까닭(여러 개 가능, 맞으면 빈 목록). OTHER_CLASS: 요청 직업과 다름, ITEM_LEVEL_OUT_OF_RANGE: 아이템 레벨 범위 밖, NOT_RECENT: activeDays 안에 확인 안 됨, NO_COMBAT_POWER: 전투력 관측 없음. 문자열이며 값이 늘 수 있음")
          List<String> reasons,
      ClassBuildCellsConditions conditions,
      ClassBuildCells cells,
      @Schema(example = "class-builds-v1") String ruleVersion) {
    static ClassBuildCellsResponse from(
        com.loakraken.stats.application.ClassBuildCellsService.Result result) {
      var cells = result.cells();
      return new ClassBuildCellsResponse(
          result.characterName(),
          result.serverName(),
          result.className(),
          result.itemLevel(),
          result.observedAt(),
          result.source().name(),
          result.inSample(),
          result.reasons().stream().map(Enum::name).toList(),
          new ClassBuildCellsConditions(
              result.itemLevelMin(),
              result.itemLevelMax(),
              result.activeDays(),
              result.braceletItemLevelMin()),
          new ClassBuildCells(
              cells.classEngraving() == null ? null : new ClassBuildNameKey(cells.classEngraving()),
              cells.engravingCombo() == null
                  ? null
                  : new ClassBuildComboKey(cells.engravingCombo()),
              nameKeys(cells.engravings()),
              new ClassBuildPointKeys(
                  valueKey(cells.evolutionPoints()),
                  valueKey(cells.enlightenmentPoints()),
                  valueKey(cells.leapPoints())),
              new ClassBuildGemKeys(valueKeys(cells.damageGems()), valueKeys(cells.cooldownGems())),
              cells.cardSet() == null
                  ? null
                  : new ClassBuildCardKey(
                      cells.cardSet().set(), cells.cardSet().pieces(), cells.cardSet().awakening()),
              cells.topStat() == null
                  ? null
                  : new ClassBuildTopStatKey(cells.topStat().stat(), cells.topStat().tie()),
              cells.braceletEligible(),
              nameKeys(cells.braceletStats())),
          ClassBuilds.RULE_VERSION);
    }

    private static List<ClassBuildNameKey> nameKeys(List<String> names) {
      return names == null ? null : names.stream().map(ClassBuildNameKey::new).toList();
    }

    private static ClassBuildValueKey valueKey(Integer value) {
      return value == null ? null : new ClassBuildValueKey(value);
    }

    private static List<ClassBuildValueKey> valueKeys(List<Integer> values) {
      return values == null ? null : values.stream().map(ClassBuildValueKey::new).toList();
    }
  }

  @Schema(description = "실제로 쓴 조건(빌드 통계 meta.conditions와 같은 기본값 규칙)")
  public record ClassBuildCellsConditions(
      BigDecimal itemLevelMin,
      @NullableResponse BigDecimal itemLevelMax,
      int activeDays,
      BigDecimal braceletItemLevelMin) {}

  @Schema(description = "항목 이름은 빌드 통계 응답과 같다. 값이 null이면 그 항목을 못 읽음(분모에서 빠짐)")
  public record ClassBuildCells(
      @Schema(description = "classEngravings cells[].name과 같음") @NullableResponse
          ClassBuildNameKey classEngravings,
      @Schema(description = "engravingCombos cells[].engravings와 같음") @NullableResponse
          ClassBuildComboKey engravingCombos,
      @Schema(description = "engravings에서 이 캐릭터가 세지는 칸들(cells[].name)") @NullableResponse
          List<ClassBuildNameKey> engravings,
      ClassBuildPointKeys arkPassivePoints,
      ClassBuildGemKeys gems,
      @Schema(description = "cardSets cells[]의 set·pieces·awakening과 같음") @NullableResponse
          ClassBuildCardKey cardSets,
      @Schema(description = "topStats cells[].name과 같음. tie는 집계의 ties에 세지는 캐릭터") @NullableResponse
          ClassBuildTopStatKey topStats,
      @Schema(
              description =
                  "아이템 레벨이 braceletItemLevelMin 이상이라 팔찌 전투 특성 집계 대상인가. false면 braceletStats는 null")
          boolean braceletEligible,
      @Schema(
              description =
                  "braceletStats에서 이 캐릭터가 세지는 칸들(cells[].name). 대상이 아니거나 팔찌를 못 읽었으면 null, 전투 특성 줄이 없으면 빈 목록")
          @NullableResponse
          List<ClassBuildNameKey> braceletStats) {}

  public record ClassBuildNameKey(String name) {}

  public record ClassBuildComboKey(@Schema(description = "각인 이름(가나다순)") List<String> engravings) {}

  public record ClassBuildValueKey(@Schema(description = "포인트 값 또는 보석 레벨") int value) {}

  public record ClassBuildPointKeys(
      @NullableResponse ClassBuildValueKey evolution,
      @NullableResponse ClassBuildValueKey enlightenment,
      @NullableResponse ClassBuildValueKey leap) {}

  @Schema(description = "보석 1개마다 한 칸(높은 레벨부터). 보석을 못 읽었으면 null, 그 효과 종류 4티어 보석이 없으면 빈 목록")
  public record ClassBuildGemKeys(
      @NullableResponse List<ClassBuildValueKey> damage,
      @NullableResponse List<ClassBuildValueKey> cooldown) {}

  public record ClassBuildCardKey(
      String set,
      int pieces,
      @Schema(description = "각성 합계(없으면 null)") @NullableResponse Integer awakening) {}

  public record ClassBuildTopStatKey(
      String name,
      @Schema(description = "가장 큰 값이 둘 이상이라 고정 순서(치명→특화→신속→제압→인내→숙련)로 골랐는가") boolean tie) {}

  @GetMapping(
      value = "/api/v1/stats/classes/{className}/builds",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "직업 상세 빌드 통계",
      description =
          "그 직업 캐릭터의 마지막 장비 요약으로 센 빌드 통계입니다(class-builds-v1): 직업 각인 비율, 각인 조합, 개별 각인 채용률, 아크 패시브 포인트, "
              + "4티어 보석(겁화·작열·광휘) 효과 종류별 레벨, 카드 세트, 주력 전투 특성(top-1), 팔찌 전투 특성. 모집단은 /api/v1/stats/*와 같고(처리정지 캐릭터 없음), "
              + "표본은 그 가운데 장비 요약이 있는 캐릭터입니다. itemLevelMin 기본값은 다른 통계(1640)와 달리 1680이고(단 itemLevelMax만 1680 미만으로 주면 1640), 실제로 쓴 값은 meta.conditions.itemLevelMin에 있습니다. "
              + "표본이 30 미만이면 sampleStatus=COLLECTING이고 항목이 모두 빈 목록입니다. "
              + "인원(보석은 개수)이 5 미만인 칸은 목록에 없습니다. 못 읽은 값은 그 항목의 분모(known)에서 빠집니다. 정의는 definitions에 있습니다. "
              + "400 INVALID_REQUEST·STATS_INVALID_RANGE, 503 STATS_UNAVAILABLE")
  @StatsController.StandardErrors
  public ApiResponse<ClassBuildsResponse> builds(
      @Parameter(description = "직업 이름(공식 표기)")
          @PathVariable
          @Size(min = 1, max = 16)
          @Pattern(regexp = "[\\p{L}]+")
          String className,
      @Valid @ModelAttribute @ParameterObject StatsFilterRequest filter,
      @Parameter(description = "직업 각인(공식 ArkPassive.Title). 주면 그 직업 각인 캐릭터만 셉니다(직업 각인 비율은 제외)")
          @RequestParam(required = false)
          @Size(min = 1, max = 32)
          @Pattern(regexp = "[\\p{L}\\p{N} ]+")
          String classEngraving,
      @Parameter(description = "항목별 상위 칸 수(기본 10, 최대 20)")
          @RequestParam(required = false)
          @Min(1)
          @Max(ClassBuilds.MAX_LIMIT)
          Integer limit,
      @Parameter(description = "팔찌 전투 특성에 넣는 최소 아이템 레벨(기본 1680). itemLevelMin보다 낮으면 itemLevelMin")
          @RequestParam(required = false)
          @DecimalMin("0")
          @DecimalMax("2000")
          BigDecimal braceletItemLevelMin) {
    var conditions = filter.toConditions().withClassName(className);
    // 빌드 통계만 기본 최소 아이템 레벨이 1680이다. 단 최대만 1680 미만으로 주면 범위가 뒤집히지 않게 공통 기본(1640)을 둔다.
    // 실제로 쓴 값은 meta.conditions.itemLevelMin에 나온다.
    if (filter.itemLevelMin() == null
        && (filter.itemLevelMax() == null
            || filter.itemLevelMax().compareTo(ClassBuilds.DEFAULT_ITEM_LEVEL_MIN) >= 0))
      conditions = conditions.withItemLevelMin(ClassBuilds.DEFAULT_ITEM_LEVEL_MIN);
    return ApiResponse.of(
        StatsSuccessCode.STATS_CLASS_BUILDS_SUCCESS,
        ClassBuildsResponse.from(
            stats.classBuilds(
                conditions,
                classEngraving == null || classEngraving.isBlank() ? null : classEngraving.strip(),
                limit == null ? ClassBuilds.DEFAULT_LIMIT : limit,
                braceletItemLevelMin)));
  }

  public record ClassBuildsResponse(
      @Schema(
              description =
                  "population은 표본(장비 요약이 있는 캐릭터, 직업 각인 필터 적용) 수, ruleVersion은 class-builds-v1")
          StatsResponses.StatsMetaResponse meta,
      String className,
      @Schema(description = "요청한 직업 각인(없으면 null)") @NullableResponse String classEngraving,
      @Schema(description = "장비 요약이 없는 캐릭터도 센 그 직업 모집단 수(/api/v1/stats/classes 인원과 같은 모집단)")
          long classPopulation,
      @Schema(description = "인원(보석은 개수)이 이 값 미만인 칸은 목록에서 뺍니다", example = "5") int minCell,
      @Schema(description = "팔찌 전투 특성에 실제로 쓴 최소 아이템 레벨", example = "1680")
          BigDecimal braceletItemLevelMin,
      @Schema(
              description = "직업 각인 비율의 표본 상태(직업 각인 필터와 무관한 직업 전체 표본 기준)",
              allowableValues = {"COLLECTING", "READY"})
          String classEngravingStatus,
      @Schema(description = "항목 이름 → 정의 한 줄") Map<String, String> definitions,
      @Schema(description = "직업 각인별 인원(직업 전체 표본)") ClassBuildNameSection classEngravings,
      @Schema(description = "각인 이름 집합이 같은 캐릭터 수") ClassBuildComboSection engravingCombos,
      @Schema(description = "각인별 채용 캐릭터 수(여러 각인에 셈)") ClassBuildNameSection engravings,
      ClassBuildArkPassivePoints arkPassivePoints,
      ClassBuildGems gems,
      ClassBuildCardSection cardSets,
      @Schema(description = "주력 전투 특성 top-1(6종, 합 100%)") ClassBuildTopStatSection topStats,
      @Schema(description = "팔찌에 그 전투 특성 줄이 있는 캐릭터 수(여러 특성에 셈, 합이 100%를 넘음)")
          ClassBuildBraceletSection braceletStats) {
    static ClassBuildsResponse from(StatsQueryService.ClassBuildsResult result) {
      var builds = result.builds();
      return new ClassBuildsResponse(
          StatsResponses.StatsMetaResponse.from(result.meta()),
          result.className(),
          result.classEngraving(),
          result.classPopulation(),
          ClassBuilds.MIN_CELL,
          result.braceletItemLevelMin(),
          result.classEngravingStatus().name(),
          result.definitions(),
          names(builds.classEngravings()),
          new ClassBuildComboSection(
              builds.engravingCombos().known(),
              hidden(builds.engravingCombos().hidden()),
              cells(
                  builds.engravingCombos(),
                  cell -> new ClassBuildComboCell(cell.key(), cell.count(), cell.share()))),
          names(builds.engravings()),
          new ClassBuildArkPassivePoints(
              values(builds.evolutionPoints()),
              values(builds.enlightenmentPoints()),
              values(builds.leapPoints())),
          new ClassBuildGems(values(builds.damageGems()), values(builds.cooldownGems())),
          new ClassBuildCardSection(
              builds.cardSets().known(),
              hidden(builds.cardSets().hidden()),
              cells(
                  builds.cardSets(),
                  cell ->
                      new ClassBuildCardCell(
                          cell.key().set(),
                          cell.key().pieces(),
                          cell.key().awakening(),
                          cell.count(),
                          cell.share()))),
          new ClassBuildTopStatSection(
              builds.topStats().known(),
              builds.topStats().ties(),
              hidden(builds.topStats().hidden()),
              nameCells(builds.topStats().cells())),
          new ClassBuildBraceletSection(
              builds.braceletStats().known(),
              builds.braceletStats().unread(),
              hidden(builds.braceletStats().hidden()),
              nameCells(builds.braceletStats().cells())));
    }

    private static ClassBuildNameSection names(ClassBuilds.Section<String> section) {
      return new ClassBuildNameSection(
          section.known(), hidden(section.hidden()), nameCells(section.cells()));
    }

    private static List<ClassBuildNameCell> nameCells(List<ClassBuilds.Cell<String>> cells) {
      return cells.stream()
          .map(cell -> new ClassBuildNameCell(cell.key(), cell.count(), cell.share()))
          .toList();
    }

    private static ClassBuildValueSection values(ClassBuilds.Section<Integer> section) {
      return new ClassBuildValueSection(
          section.known(),
          hidden(section.hidden()),
          cells(section, cell -> new ClassBuildValueCell(cell.key(), cell.count(), cell.share())));
    }

    private static ClassBuildHidden hidden(ClassBuilds.Hidden hidden) {
      return new ClassBuildHidden(hidden.count(), hidden.share());
    }

    private static <T, R> List<R> cells(
        ClassBuilds.Section<T> section, Function<ClassBuilds.Cell<T>, R> mapper) {
      return section.cells().stream().map(mapper).toList();
    }
  }

  @Schema(
      description =
          "인원(보석은 개수)이 minCell 미만이라 cells에서 뺀 칸들의 합. 화면에 '표본이 적은 항목은 숨김'으로 보일 때 씁니다(limit으로 잘린 칸은 들지 않음)")
  public record ClassBuildHidden(
      @Schema(
              description =
                  "숨긴 칸들의 count 합. 여러 값에 세는 항목(engravings·braceletStats)은 한 캐릭터가 여러 칸에 들어 사람 수가 아님")
          long count,
      @Schema(description = "known 대비 %, 소수 둘째 자리. known이 0이면 null") @NullableResponse
          BigDecimal share) {}

  @Schema(description = "known: 이 항목의 분모(값을 읽은 캐릭터 수)")
  public record ClassBuildNameSection(
      long known, ClassBuildHidden hidden, List<ClassBuildNameCell> cells) {}

  public record ClassBuildNameCell(
      String name,
      long count,
      @Schema(description = "known 대비 %, 소수 둘째 자리", example = "41.25") BigDecimal share) {}

  public record ClassBuildComboSection(
      long known, ClassBuildHidden hidden, List<ClassBuildComboCell> cells) {}

  public record ClassBuildComboCell(
      @Schema(description = "각인 이름(가나다순)") List<String> engravings,
      long count,
      @Schema(description = "known 대비 %, 소수 둘째 자리") BigDecimal share) {}

  @Schema(description = "known: 분모. 보석 항목은 그 효과 종류의 보석 수")
  public record ClassBuildValueSection(
      long known, ClassBuildHidden hidden, List<ClassBuildValueCell> cells) {}

  public record ClassBuildValueCell(
      @Schema(description = "포인트 값 또는 보석 레벨") int value,
      long count,
      @Schema(description = "known 대비 %, 소수 둘째 자리") BigDecimal share) {}

  public record ClassBuildArkPassivePoints(
      ClassBuildValueSection evolution,
      ClassBuildValueSection enlightenment,
      ClassBuildValueSection leap) {}

  @Schema(description = "4티어 보석(겁화·작열·광휘) 1개 단위, 효과 종류별 레벨 분포")
  public record ClassBuildGems(
      @Schema(description = "피해 증가 보석") ClassBuildValueSection damage,
      @Schema(description = "재사용 대기시간 감소 보석") ClassBuildValueSection cooldown) {}

  public record ClassBuildCardSection(
      long known, ClassBuildHidden hidden, List<ClassBuildCardCell> cells) {}

  public record ClassBuildCardCell(
      String set,
      @Schema(description = "세트 단계", example = "6") int pieces,
      @Schema(description = "각성 합계(없으면 null)", example = "30") @NullableResponse Integer awakening,
      long count,
      @Schema(description = "known 대비 %, 소수 둘째 자리") BigDecimal share) {}

  @Schema(description = "ties: 가장 큰 값이 둘 이상이라 고정 순서(치명→특화→신속→제압→인내→숙련)로 고른 캐릭터 수(known에 포함)")
  public record ClassBuildTopStatSection(
      long known, long ties, ClassBuildHidden hidden, List<ClassBuildNameCell> cells) {}

  @Schema(
      description =
          "known: 기준 레벨 이상에서 팔찌를 읽은 캐릭터 수(분모). unread: 기준 레벨 이상인데 팔찌를 못 읽은(없는) 캐릭터 수(0이면 분모 = 조건에 맞는 전체)")
  public record ClassBuildBraceletSection(
      long known, long unread, ClassBuildHidden hidden, List<ClassBuildNameCell> cells) {}

  @Configuration(proxyBeanMethods = false)
  static class ClassBuildsOpenApiConfiguration {
    @Bean
    OpenApiCustomizer classBuildsResponseContract() {
      return document -> {
        ResponseContracts.apply(ClassBuildsResponse.class, document.getComponents().getSchemas());
        ResponseContracts.apply(
            ClassBuildCellsResponse.class, document.getComponents().getSchemas());
      };
    }
  }
}
