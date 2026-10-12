package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
// 영수증·젬 가공·치명 계산은 운영에서 꺼질 수 있지만 계약 산출물(openapi-contract)에는 경로·스키마가 있어야 웹이 화면을 만든다(프론트 10-10 02:31).
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "receipt.enabled=true",
      "gem.enabled=true",
      "crit-speed.enabled=true",
      "combat-power-sim.enabled=true",
      "ranking.collectibles.enabled=true",
      "ranking.rosters.enabled=true",
      "ranking.guilds.enabled=true",
      "ranking.titles.enabled=true",
      "loakraken.features.auction-search=true"
    })
class OpenApiIntegrationTest {
  @LocalServerPort private int port;

  @Test
  void OpenAPI_명세에_서비스_정보와_공통_오류_스키마가_포함된다() throws Exception {
    var response = request("/v3/api-docs");
    assertThat(response.statusCode()).isEqualTo(200);
    var document = JsonMapper.builder().build().readTree(response.body());
    assertThat(document.path("openapi").asText()).startsWith("3.1.");
    assertThat(document.at("/info/title").asText()).isEqualTo("LOAKraken API");
    assertThat(document.at("/components/schemas/ErrorResponse/properties/code").isMissingNode())
        .isFalse();
    assertThat(document.at("/components/schemas/ErrorResponse/properties/data/type").asText())
        .isEqualTo("null");
    assertThat(document.path("paths").has("/test/success")).isFalse();
    assertThat(document.path("paths").has("/actuator/health")).isFalse();
    // 유입 제한(#108): 모든 작업에 429 CLIENT_RATE_LIMITED가 있고, 기존 429 설명은 유지한다.
    for (var path : document.path("paths").properties())
      for (var operation : path.getValue().properties())
        assertThat(operation.getValue().at("/responses/429/description").asText())
            .as(path.getKey() + " " + operation.getKey())
            .contains("CLIENT_RATE_LIMITED");
    assertThat(
            document
                .at("/paths/~1api~1v1~1characters~1search/get/responses/429/description")
                .asText())
        .startsWith("LOST_ARK_RATE_LIMITED");
    ApiContractFixture.write("openapi.json", response.body());
    // 다른 사이트 이름 금지(#261): OpenAPI 설명·예시는 계약 산출물로 웹에 넘어간다.
    assertThat(response.body()).doesNotContain(SiteNames.FORBIDDEN);
    assertThat(
            document
                .at("/components/schemas/CharacterLoadoutResponse/properties/gems/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/GemsResponse/type").asText()).isEqualTo("object");
    assertThat(
            document
                .at("/components/schemas/EquipmentResponse/properties/detail/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/EquipmentDetailResponse/required").toString())
        .contains(
            "parserVersion",
            "refineLevel",
            "quality",
            "itemLevel",
            "tier",
            "arkPassivePoint",
            "effects",
            "stoneEngravings");
    assertThat(
            document
                .at("/components/schemas/EquipmentDetailResponse/properties/quality/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/CharacterResponse/required").toString())
        .contains("characterName", "combatPower", "analysis", "coverage", "siblings");
    assertThat(
            document
                .at("/components/schemas/CharacterResponse/properties/siblings/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(
            document
                .at(
                    "/components/schemas/CharacterObservationCoverageResponse/properties/siblings/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/CharacterLoadoutResponse/required").toString())
        .contains("skills");
    assertThat(document.at("/components/schemas/SkillResponse/required").toString())
        .contains("name", "partBreakLevel", "counter", "tripods", "rune", "gemEffects");
    assertThat(
            document
                .at("/components/schemas/CharacterResponse/properties/skillPoints/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(
            document
                .at("/components/schemas/CharacterObservationCoverageResponse/required")
                .toString())
        .contains("skills", "avatars", "collectibles");
    assertThat(document.at("/components/schemas/CharacterResponse/required").toString())
        .contains("tendencies", "decorations", "honorPoint", "townName", "avatars", "collectibles");
    // 자동완성(#372): 필수 필드와 nullable 초상(프론트 10-12 05:32 계약 검증).
    assertThat(document.at("/components/schemas/CharacterSuggestResponse/required").toString())
        .contains("items");
    assertThat(document.at("/components/schemas/CharacterSuggestItemResponse/required").toString())
        .contains("characterName", "serverName", "className", "itemLevel", "characterImage");
    assertThat(
            document
                .at(
                    "/components/schemas/CharacterSuggestItemResponse/properties/characterImage/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    // 원정대 캐릭터별 전투력(L19): 필수와 nullable(기록 없음·쿨다운 없음).
    assertThat(document.at("/components/schemas/RosterCombatPowerResponse/required").toString())
        .contains("items", "missingCount", "refreshAvailableAt");
    assertThat(
            document.at("/components/schemas/RosterCombatPowerRefreshResponse/required").toString())
        .contains("items", "fetched", "remaining", "refreshAvailableAt");
    assertThat(document.at("/components/schemas/RosterCombatPowerItemResponse/required").toString())
        .contains("characterName", "serverName", "combatPower", "combatPowerObservedAt");
    for (var field : List.of("combatPower", "combatPowerObservedAt", "className", "itemLevel"))
      assertThat(
              document
                  .at(
                      "/components/schemas/RosterCombatPowerItemResponse/properties/"
                          + field
                          + "/anyOf/1/type")
                  .asText())
          .as(field)
          .isEqualTo("null");
    assertThat(
            document
                .at(
                    "/components/schemas/RosterCombatPowerResponse/properties/refreshAvailableAt/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    // 아크 그리드 효율(L29·L39): 캐릭터 응답의 nullable 필드, 필수와 nullable.
    assertThat(
            document
                .at(
                    "/components/schemas/CharacterResponse/properties/arkGridEfficiency/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/CharacterResponse/required").toString())
        .contains("braceletEfficiency", "arkGridEfficiency");
    assertThat(document.at("/components/schemas/ArkGridEfficiencyResponse/required").toString())
        .contains(
            "percent", "coresPercent", "status", "reasonCode", "reason", "lines", "ruleVersion");
    for (var field : List.of("percent", "coresPercent", "reasonCode"))
      assertThat(
              document
                  .at(
                      "/components/schemas/ArkGridEfficiencyResponse/properties/"
                          + field
                          + "/anyOf/1/type")
                  .asText())
          .as(field)
          .isEqualTo("null");
    assertThat(document.at("/components/schemas/ArkGridLineEfficiencyResponse/required").toString())
        .contains("slotIndex", "text", "percent", "status", "reasonCode", "reason");
    for (var field : List.of("slotIndex", "percent", "reasonCode", "reason"))
      assertThat(
              document
                  .at(
                      "/components/schemas/ArkGridLineEfficiencyResponse/properties/"
                          + field
                          + "/anyOf/1/type")
                  .asText())
          .as(field)
          .isEqualTo("null");
    // 악세 경매 검색기(N13): 필수와 nullable.
    assertThat(document.at("/components/schemas/AccessorySearchResponse/required").toString())
        .contains(
            "ruleVersion",
            "totalCount",
            "page",
            "observedAt",
            "cached",
            "complete",
            "scannedPages",
            "nextPage",
            "items");
    assertThat(
            document
                .at("/components/schemas/AccessorySearchResponse/properties/nextPage/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    for (var field :
        List.of(
            "grade",
            "quality",
            "enlightenment",
            "mainStat",
            "mainStatRangePct",
            "tradeAllowCount",
            "buyPrice",
            "bidStartPrice",
            "endDate"))
      assertThat(
              document
                  .at(
                      "/components/schemas/AccessorySearchItemResponse/properties/"
                          + field
                          + "/anyOf/1/type")
                  .asText())
          .as(field)
          .isEqualTo("null");
    for (var field : List.of("option", "shortName", "value", "level"))
      assertThat(
              document
                  .at(
                      "/components/schemas/AccessorySearchPolishResponse/properties/"
                          + field
                          + "/anyOf/1/type")
                  .asText())
          .as(field)
          .isEqualTo("null");
    assertThat(
            document
                .at(
                    "/paths/~1api~1v1~1markets~1auctions~1accessories~1search/post/responses/503/description")
                .asText())
        .contains("MARKET_SEARCH_BUSY");
    var search = document.at("/paths/~1api~1v1~1characters~1search/get/parameters");
    assertThat(search.toString()).contains("includeSiblings");
    assertThat(document.at("/paths/~1api~1v1~1characters~1history/get/responses").has("503"))
        .isTrue();
    assertThat(document.at("/components/schemas/PointResponse/required").toString())
        .contains("observedAt", "lastConfirmedAt", "itemLevel", "combatPower");
    assertThat(document.at("/components/schemas/CharacterHistoryResponse/required").toString())
        .contains("points", "titles");
    var siblings = document.at("/paths/~1api~1v1~1characters~1siblings/get");
    assertThat(siblings.path("responses").has("404")).isTrue();
    assertThat(document.at("/components/schemas/CharacterSiblingsResponse/required").toString())
        .contains("characterName", "siblings", "fetchedAt", "refreshAvailableAt");
    for (var schema : java.util.List.of("CharacterResponse", "CharacterSiblingsResponse"))
      assertThat(
              document
                  .at(
                      "/components/schemas/"
                          + schema
                          + "/properties/refreshAvailableAt/anyOf/1/type")
                  .asText())
          .isEqualTo("null");
    assertThat(
            document
                .at("/components/schemas/CharacterObservationCoverageResponse/required")
                .toString())
        .contains("equipment", "engravings", "gems", "cards", "arkPassive", "arkGrid", "siblings");
    assertThat(document.at("/components/schemas/ObservationSectionResponse/required").toString())
        .contains("state", "collections");
    assertThat(document.at("/components/schemas/CharacterAnalysisResponse/required").toString())
        .contains(
            "diagnostics", "equipment", "position", "positionEvidence", "positionRuleVersion");
    assertThat(
            document
                .at(
                    "/components/schemas/CharacterAnalysisResponse/properties/position/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/EquipmentSummaryResponse/required").toString())
        .contains(
            "detailCount",
            "detailUnavailableCount",
            "refineDistribution",
            "averageQuality",
            "arkPassivePoints");
    assertThat(
            document
                .at(
                    "/components/schemas/EquipmentSummaryResponse/properties/averageQuality/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/DiagnosticFindingResponse/required").toString())
        .contains("code", "category", "evidencePath", "observedCount", "message");
    assertThat(
            document.at("/components/schemas/CharacterSnapshotHistoryResponse/required").toString())
        .contains("items", "nextCursor");
    assertThat(
            document
                .at(
                    "/components/schemas/CharacterSnapshotHistoryResponse/properties/nextCursor/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/SnapshotSummaryResponse/required").toString())
        .contains("snapshotId", "fetchedAt", "className", "itemLevel", "combatPower");
    assertThat(
            document
                .at(
                    "/components/schemas/SnapshotSummaryResponse/properties/combatPower/anyOf/1/type")
                .asText())
        .isEqualTo("null");
  }

  @Test
  void Swagger_UI_정적_페이지에_접근한다() throws Exception {
    var response = request("/swagger-ui/index.html");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("Swagger UI");
  }

  @Test
  void 거래소_가격의_출처와_null_계약을_명시한다() throws Exception {
    var document = JsonMapper.builder().build().readTree(request("/v3/api-docs").body());
    for (var path : java.util.List.of("/api/v1/markets/items", "/api/v1/markets/trades")) {
      var operation = document.path("paths").path(path).path("get");
      assertThat(operation.isMissingNode()).isFalse();
      assertThat(operation.path("responses").toString())
          .contains("200", "400", "429", "502", "503", "504");
    }
    assertThat(document.at("/components/schemas/MarketPriceResponse/required").toString())
        .contains("source", "observedAt", "windowFrom", "windowTo", "search", "items");
    assertThat(
            document
                .at("/components/schemas/MarketPriceResponse/properties/windowFrom/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(
            document
                .at("/components/schemas/MarketPriceItem/properties/currentMinPrice/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/MarketPriceItem/required").toString())
        .contains("bundleCount", "recentPrice", "yDayAvgPrice", "currentMinPrice");
  }

  @Test
  void 거래소_검색옵션의_필수필드와_하위목록_null을_명시한다() throws Exception {
    var document = JsonMapper.builder().build().readTree(request("/v3/api-docs").body());
    var operation = document.path("paths").path("/api/v1/markets/options").path("get");
    assertThat(operation.path("responses").toString()).contains("200", "429", "502", "503", "504");
    assertThat(document.at("/components/schemas/MarketOptionsResponse/required").toString())
        .contains("observedAt", "categories", "itemGrades", "itemTiers", "classes");
    assertThat(
            document
                .at("/components/schemas/MarketCategoryResponse/properties/subs/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/MarketSubcategoryResponse/required").toString())
        .contains("code", "codeName");
  }

  @Test
  void 운영_지표_엔드포인트는_기본으로_공개하지_않는다() throws Exception {
    assertThat(request("/actuator/metrics").statusCode()).isEqualTo(404);
    assertThat(request("/actuator/prometheus").statusCode()).isEqualTo(404);
  }

  @Test
  void 보석_계획의_공식_규칙과_필수_수량_계약을_명시한다() throws Exception {
    var document = JsonMapper.builder().build().readTree(request("/v3/api-docs").body());
    var operation = document.path("paths").path("/api/v1/gems/synthesis-plans").path("post");
    assertThat(operation.path("requestBody").path("required").asBoolean()).isTrue();
    assertThat(operation.path("responses").toString()).contains("200", "400", "406", "415", "500");
    assertThat(document.at("/components/schemas/GemSynthesisRequest/required").toString())
        .contains("tier", "baseLevel", "targetLevel", "targetQuantity", "inventory");
    assertThat(document.at("/components/schemas/GemSynthesisResponse/required").toString())
        .contains(
            "ruleVersion",
            "rulesVerifiedOn",
            "sources",
            "status",
            "additionalBaseQuantity",
            "inventory",
            "steps",
            "outcomesPerSynthesis");
    assertThat(document.at("/components/schemas/GemSynthesisStepResponse/required").toString())
        .contains("inputLevel", "outputLevel", "inputQuantity", "outputQuantity");
    assertThat(
            document
                .at("/components/schemas/GemSynthesisRequest/properties/validLevels")
                .isMissingNode())
        .isTrue();
  }

  @Test
  void 치명타_공이속_계산은_관측과_가정_분리와_null_계약을_명시한다() throws Exception {
    var document = JsonMapper.builder().build().readTree(request("/v3/api-docs").body());
    var operation = document.path("paths").path("/api/v1/characters/crit-speed").path("post");
    assertThat(operation.path("requestBody").path("required").asBoolean()).isTrue();
    assertThat(operation.path("responses").toString()).contains("200", "400", "404", "415", "429");
    assertThat(document.at("/components/schemas/CritSpeedRequest/required").toString())
        .contains("characterName");
    assertThat(
            document
                .at("/components/schemas/CritSpeedAssumptions/properties/critSynergyCount/maximum")
                .asInt())
        .isEqualTo(3);
    assertThat(document.at("/components/schemas/CritSpeedResponse/required").toString())
        .contains("ruleVersion", "sources", "crit", "speed", "limitations");
    assertThat(document.at("/components/schemas/CritSpeedSourceResponse/required").toString())
        .contains("kind", "category", "axis", "value", "text");
    assertThat(
            document
                .at(
                    "/components/schemas/CritSpeedTargetResponse/properties/requiredCritStat/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/CritSpeedSupersonicResponse/required").toString())
        .contains("optimalSpeed", "requiredSwiftness", "requiredAdditionalSwiftness");
    assertThat(document.at("/components/schemas/CritSpeedSpeedResponse/required").toString())
        .contains("swiftnessPerPercent", "swiftnessTable");
    assertThat(document.at("/components/schemas/CritSpeedSwiftnessRowResponse/required").toString())
        .contains("swiftness", "attack", "move", "supersonicEvolutionDamage", "current", "optimal");
    assertThat(
            document
                .at("/components/schemas/CritSpeedAssumptions/properties/extraAttackSpeed/minimum")
                .asInt())
        .isEqualTo(-100);
    assertThat(document.at("/components/schemas/StatResponse/required").toString())
        .contains("tooltip");
    assertThat(document.at("/components/schemas/PassiveEffectResponse/required").toString())
        .contains("effect");
  }

  @Test
  void 조회수는_일수_검증과_합계_계약을_명시한다() throws Exception {
    var document = JsonMapper.builder().build().readTree(request("/v3/api-docs").body());
    var operation = document.path("paths").path("/api/v1/characters/views").path("get");
    assertThat(operation.path("parameters").toString()).contains("characterName", "days");
    assertThat(operation.path("responses").toString()).contains("200", "400", "503");
    assertThat(document.at("/components/schemas/CharacterViewsResponse/required").toString())
        .contains("days", "rangeTotal", "total", "from", "to");
  }

  @Test
  void 아크_그리드_상세는_코어_옵션과_의지력_null_계약을_명시한다() throws Exception {
    var document = JsonMapper.builder().build().readTree(request("/v3/api-docs").body());
    assertThat(document.at("/components/schemas/ArkGridResponse/required").toString())
        .contains("willpower");
    assertThat(document.at("/components/schemas/GridSlotResponse/required").toString())
        .contains(
            "coreType",
            "supplyWillpower",
            "usedWillpower",
            "options",
            "activeOptionCount",
            "optionCount");
    assertThat(document.at("/components/schemas/GridEffectResponse/required").toString())
        .contains("value", "percent");
    assertThat(document.at("/components/schemas/GridGemResponse/required").toString())
        .contains("gemPoint", "corePoint", "requiredWillpower", "options");
    assertThat(
            document
                .at("/components/schemas/GridSlotResponse/properties/supplyWillpower/anyOf/1/type")
                .asText())
        .isEqualTo("null");
  }

  @Test
  void 홈_일정과_소식은_nullable_시각과_분류_계약을_명시한다() throws Exception {
    var document = JsonMapper.builder().build().readTree(request("/v3/api-docs").body());
    // 일정 보상 상세(F-9, PM-백엔드 #118 P2): rewardGroups 필수, minItemLevel nullable, timed 필수
    for (var schema : List.of("HomeIslandResponse", "HomeTimedResponse", "HomeOtherResponse"))
      assertThat(document.at("/components/schemas/" + schema + "/required").toString())
          .as(schema)
          .contains("rewardGroups");
    assertThat(document.at("/components/schemas/HomeRewardGroupResponse/required").toString())
        .contains("minItemLevel", "rewards");
    assertThat(
            document
                .at(
                    "/components/schemas/HomeRewardGroupResponse/properties/minItemLevel/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/HomeRewardResponse/required").toString())
        .contains("name", "timed");
    assertThat(document.path("paths").path("/api/v1/home/schedule").path("get").isMissingNode())
        .isFalse();
    assertThat(
            document
                .path("paths")
                .path("/api/v1/home/news")
                .path("get")
                .path("parameters")
                .toString())
        .contains("limit", "category");
    assertThat(document.at("/components/schemas/HomeScheduleResponse/required").toString())
        .contains(
            "adventureIslands",
            "fieldBosses",
            "chaosGates",
            "others",
            "goldIslandDays",
            "weekly",
            "coveredFrom");
    assertThat(document.at("/components/schemas/HomeIslandResponse/required").toString())
        .contains("rewardKinds", "nextAt", "startTimes");
    assertThat(
            document
                .at("/components/schemas/HomeIslandResponse/properties/nextAt/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/HomeNoticeResponse/required").toString())
        .contains("maintenanceDate", "maintenanceDateText", "category");
    assertThat(document.at("/components/schemas/HomeEventResponse/required").toString())
        .contains("startAt", "endAt", "rewardAt", "thumbnail");
  }

  @Test
  void 시세_보드는_탭_정렬_검증과_null_등락률_계약을_명시한다() throws Exception {
    var document = JsonMapper.builder().build().readTree(request("/v3/api-docs").body());
    var board = document.path("paths").path("/api/v1/markets/board").path("get");
    assertThat(board.path("parameters").toString())
        .contains("tab", "sort", "default|high|low|rise|fall");
    assertThat(document.path("paths").path("/api/v1/markets/tabs").path("get").isMissingNode())
        .isFalse();
    assertThat(document.at("/components/schemas/MarketBoardResponse/required").toString())
        .contains("ruleVersion", "changeBasis", "scannedItems", "complete", "items");
    assertThat(document.at("/components/schemas/MarketBoardItemResponse/required").toString())
        .contains("itemId", "changeDay", "changeWeek", "spark7", "historyAvailable");
    assertThat(
            document
                .at("/components/schemas/MarketBoardItemResponse/properties/changeDay/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/MarketTabResponse/required").toString())
        .contains("tab", "label", "categoryCode", "defaultTier");
  }

  @Test
  void 상품_시세_이력은_부분_집계와_null_요약_계약을_명시한다() throws Exception {
    var document = JsonMapper.builder().build().readTree(request("/v3/api-docs").body());
    var operation =
        document.path("paths").path("/api/v1/markets/items/{itemId}/history").path("get");
    assertThat(operation.path("responses").toString()).contains("200", "400", "404", "429", "503");
    assertThat(operation.path("parameters").get(0).path("schema").path("maximum").asLong())
        .isEqualTo(999_999_999L);
    assertThat(document.at("/components/schemas/MarketItemHistoryResponse/required").toString())
        .contains("itemId", "observedAt", "baseDate", "variants");
    assertThat(document.at("/components/schemas/MarketDailyStatResponse/required").toString())
        .contains("date", "averagePrice", "tradeCount", "partial");
    assertThat(
            document
                .at(
                    "/components/schemas/MarketItemSummaryResponse/properties/dayChangeRate/anyOf/1/type")
                .asText())
        .isEqualTo("null");
  }

  @Test
  void 재료_단가는_요청_필수값과_미계산_null_계약을_명시한다() throws Exception {
    var document = JsonMapper.builder().build().readTree(request("/v3/api-docs").body());
    var operation = document.path("paths").path("/api/v1/markets/material-prices").path("post");
    assertThat(operation.path("requestBody").path("required").asBoolean()).isTrue();
    assertThat(operation.path("responses").toString()).contains("200", "400", "415", "429", "503");
    assertThat(document.at("/components/schemas/MaterialPriceRequest/required").toString())
        .contains("priceBasis", "names");
    assertThat(document.at("/components/schemas/MaterialPriceResponse/required").toString())
        .contains("priceBasis", "items");
    assertThat(document.at("/components/schemas/MaterialPriceItemResponse/required").toString())
        .contains("name", "status", "unitPriceGold", "contentsPerItem", "observedAt");
    assertThat(
            document
                .at(
                    "/components/schemas/MaterialPriceItemResponse/properties/unitPriceGold/anyOf/1/type")
                .asText())
        .isEqualTo("null");
  }

  @Test
  void 재료_견적은_필수_수량과_미계산_null_계약을_명시한다() throws Exception {
    var document = JsonMapper.builder().build().readTree(request("/v3/api-docs").body());
    var operation = document.path("paths").path("/api/v1/markets/material-quotes").path("post");
    assertThat(operation.path("requestBody").path("required").asBoolean()).isTrue();
    assertThat(operation.path("responses").toString()).contains("200", "400", "415", "429", "503");
    assertThat(document.at("/components/schemas/MaterialQuoteRequest/required").toString())
        .contains("priceBasis", "items");
    assertThat(document.at("/components/schemas/MaterialQuoteItemRequest/required").toString())
        .contains("itemId", "requiredQuantity", "ownedQuantity", "search");
    assertThat(document.at("/components/schemas/MaterialQuoteResponse/required").toString())
        .contains("ruleVersion", "priceBasis", "complete", "knownCostGold", "totalCostGold");
    assertThat(
            document
                .at(
                    "/components/schemas/MaterialQuoteResponse/properties/totalCostGold/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(
            document
                .at(
                    "/components/schemas/MaterialQuoteLineResponse/properties/purchase/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/MaterialQuoteLineResponse/required").toString())
        .contains("status", "observedAt", "bundleCount", "pricePerBundle", "purchase");
    assertThat(
            document
                .at("/components/schemas/MaterialQuoteRequest/properties/uniqueItemIds")
                .isMissingNode())
        .isTrue();
  }

  @Test
  void 통계_랭킹은_필수필드_null_허용값_계약을_명시한다() throws Exception {
    var document = JsonMapper.builder().build().readTree(request("/v3/api-docs").body());
    for (var path :
        new String[] {
          "/api/v1/stats/summary",
          "/api/v1/stats/classes",
          "/api/v1/stats/servers",
          "/api/v1/stats/distribution",
          "/api/v1/stats/snapshots",
          "/api/v1/rankings",
          "/api/v1/characters/rank"
        })
      assertThat(document.at("/paths/" + path.replace("/", "~1") + "/get").isMissingNode())
          .as(path)
          .isFalse();
    assertThat(document.at("/components/schemas/StatsMetaResponse/required").toString())
        .contains("population", "conditions", "sampleStatus", "coverage", "minSample");
    assertThat(
            document
                .at("/components/schemas/StatsMetaResponse/properties/sampleStatus/enum")
                .toString())
        .contains("COLLECTING", "READY");
    assertThat(document.at("/components/schemas/StatsSummaryResponse/required").toString())
        .contains("itemLevelMedian", "combatPowerP90", "supportShare", "gemMeanLevel");
    assertThat(
            document
                .at(
                    "/components/schemas/StatsSummaryResponse/properties/combatPowerP90/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(
            document
                .at("/components/schemas/StatsRankedCharacterResponse/properties/tier/anyOf/0/enum")
                .toString())
        .contains("TOP1", "REST");
    assertThat(document.at("/components/schemas/StatsRankEntryResponse/required").toString())
        .contains("metric", "scope", "scopeValue", "rank", "total", "topPercent", "tier");
    assertThat(document.at("/components/schemas/StatsCharacterRankResponse/required").toString())
        .contains("rankVisibility", "included", "entries");
    assertThat(
            document
                .at("/components/schemas/StatsCharacterRankResponse/properties/rankVisibility/enum")
                .toString())
        .contains("VISIBLE", "HIDDEN");
    assertThat(document.at("/components/schemas/StatsClassRowResponse/required").toString())
        .contains("combatPower", "supportShare", "sampleStatus");
  }

  @Test
  void 원정대_카드_기록은_고유_스키마와_필수필드_허용값을_명시한다() throws Exception {
    var document = JsonMapper.builder().build().readTree(request("/v3/api-docs").body());
    var schemas = document.at("/components/schemas");
    assertThat(schemas.at("/CardCollectionResponse/required").toString())
        .contains("position", "rosterCharacters", "observedCharacters", "elementRecords", "cards");
    assertThat(schemas.at("/CardCollectionResponse/properties/position/anyOf/0/enum").toString())
        .contains("DEALER", "SUPPORT");
    assertThat(schemas.at("/CardCollectionResponse/properties/position/anyOf/1/type").asText())
        .isEqualTo("null");
    assertThat(schemas.at("/CardCollectionElementSlotResponse/properties/element/enum").toString())
        .contains("LIGHT", "FIRE");
    assertThat(schemas.at("/CardCollectionElementSlotResponse/required").toString())
        .contains("element", "setName", "record");
    assertThat(schemas.at("/CardCollectionCardResponse/required").toString())
        .contains("observedAt", "recordedBy", "awakeCount");
    assertThat(schemas.at("/CardCollectionSetEffectResponse/required").toString())
        .contains("pieces", "awakening", "description", "active");
    assertThat(schemas.at("/CardCollectionSetRecordResponse/required").toString())
        .contains("activeAwakening", "recordedBy");
    // 기존 armory 스키마는 그대로다.
    assertThat(schemas.at("/CardResponse/properties/slot").isMissingNode()).isFalse();
    assertThat(schemas.at("/CardResponse/properties/recordedBy").isMissingNode()).isTrue();
  }

  @Test
  void 치명타_피해와_스킬_공격_타입_계약을_명시한다() throws Exception {
    var schemas =
        JsonMapper.builder()
            .build()
            .readTree(request("/v3/api-docs").body())
            .at("/components/schemas");
    assertThat(schemas.at("/CritSpeedCritResponse/required").toString()).contains("damage");
    assertThat(schemas.at("/CritSpeedCritDamageResponse/required").toString())
        .contains("base", "observed", "total");
    // 스킬별 치명타 적중률(#168): 추가 필드, 모르는 값은 null
    assertThat(schemas.at("/CritSpeedResponse/required").toString()).contains("skills");
    assertThat(schemas.at("/CritSpeedSkillResponse/required").toString())
        .contains("name", "base", "total", "effective", "tripods", "conditional");
    assertThat(schemas.at("/CritSpeedSkillResponse/properties/total/anyOf/1/type").asText())
        .isEqualTo("null");
    assertThat(schemas.at("/CritSpeedSkillTripodResponse/required").toString())
        .contains("name", "value", "text");
    assertThat(schemas.at("/SkillResponse/required").toString()).contains("attackType");
    assertThat(schemas.at("/SkillResponse/properties/attackType/anyOf/0/enum").toString())
        .contains("BACK_ATTACK", "HEAD_ATTACK", "NONE");
  }

  private HttpResponse<String> request(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
