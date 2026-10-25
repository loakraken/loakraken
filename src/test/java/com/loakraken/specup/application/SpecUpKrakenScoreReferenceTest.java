package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.CombatPowerSimulationService.Change;
import com.loakraken.character.application.CombatPowerSimulationService.Kind;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CombatPowerPolishValues;
import com.loakraken.character.domain.CombatPowerRefineModel;
import com.loakraken.character.domain.KrakenScoreEngine;
import com.loakraken.character.domain.KrakenScoreInputMapper;
import com.loakraken.character.infrastructure.ArmoryRawFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 스펙업 Δ크라켄 점수가 비교 기준 B 스펙업 추천 화면의 ▲점수와 같은지(레퍼런스 결과 표, 같은 시각 기준 캐릭터 원문 l01·l05). ▲ = 반올림 전 점수 차를 소수
 * 둘째 자리로. 보석·각인·재련 6부위, 그리고 #575의 장신구 연마 교체·어빌리티 스톤·깨달음 카르마·완갑 재련.
 */
class SpecUpKrakenScoreReferenceTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @ParameterizedTest(name = "{0} {1} {2} → {3} ▲{4}")
  @CsvSource({
    // 기준 캐릭터 1(l01) 보석: 스킬 이름(딜 보석) · 목표 레벨
    "l01, GEM, 블레이드 버스트, 7, 98.81",
    "l01, GEM, 블레이드 버스트, 8, 199.79",
    "l01, GEM, 블레이드 버스트, 9, 301.10",
    "l01, GEM, 블레이드 버스트, 10, 402.75",
    "l01, GEM, 블레이드 댄스, 7, 19.99",
    "l01, GEM, 블레이드 댄스, 10, 85.73",
    "l01, GEM, 터닝 슬래쉬, 7, 15.84",
    "l01, GEM, 윈드 컷, 7, 11.69",
    // 기준 캐릭터 1 각인: 이름 · 목표 레벨
    "l01, ENGRAVING, 기습의 대가, 1, 25.71",
    "l01, ENGRAVING, 기습의 대가, 4, 102.85",
    "l01, ENGRAVING, 아드레날린, 4, 24.18",
    "l01, ENGRAVING, 돌격대장, 1, 25.71",
    "l01, ENGRAVING, 원한, 1, 24.91",
    "l01, ENGRAVING, 원한, 4, 99.63",
    // 기준 캐릭터 1 재련: 부위 · 목표 단계
    "l01, REFINE, 무기, 20, 44.92",
    "l01, REFINE, 장갑, 20, 9.90",
    "l01, REFINE, 어깨, 20, 8.76",
    "l01, REFINE, 투구, 20, 8.24",
    "l01, REFINE, 하의, 20, 7.12",
    "l01, REFINE, 상의, 20, 6.58",
    // 기준 캐릭터 2(l05) 재련
    "l05, REFINE, 무기, 23, 37.04",
    "l05, REFINE, 어깨, 21, 7.41",
    "l05, REFINE, 투구, 21, 6.97",
    "l05, REFINE, 하의, 21, 6.03",
    "l05, REFINE, 상의, 21, 5.56",
    "l05, REFINE, 장갑, 22, 8.56",
  })
  void 레퍼런스_스펙업_추천의_점수_차와_같다(String id, String kind, String target, int level, String expected)
      throws Exception {
    var doc =
        MAPPER.readTree(
            new ClassPathResource("kraken-score/" + id + ".json")
                .getContentAsString(StandardCharsets.UTF_8));
    var input = KrakenScoreInputMapper.from(doc.path("raw"));
    var loadout = ArmoryRawFixtures.loadout(doc.path("raw"));
    var scorer = new SpecUpKrakenScore(tables(), refine(), true);
    double before = KrakenScoreEngine.dealerScore(input, tables());
    var context = new SpecUpKrakenScore.Context(input, before, null);
    var change =
        switch (kind) {
          case "GEM" -> change(Kind.GEM_LEVEL, damageGemSlot(loadout, target), null, level, null);
          case "ENGRAVING" ->
              new Change(
                  Kind.ENGRAVING_LEVEL,
                  null,
                  null,
                  null,
                  level,
                  null,
                  null,
                  null,
                  null,
                  target,
                  null,
                  null,
                  null,
                  null);
          default ->
              change(Kind.EQUIPMENT_REFINE, null, equipmentIndex(loadout, target), null, level);
        };
    var delta = scorer.delta(context, loadout, change);
    assertThat(delta).as("계산 가능").isNotNull();
    // ▲ = 반올림 전 점수 차를 소수 둘째 자리로(화면 점수끼리의 차가 아님 — 레퍼런스 최종 점수도 지금 + 반올림 전 차).
    assertThat(SpecUpKrakenScore.round2(delta))
        .as("반올림 전 차 %s", delta)
        .isEqualByComparingTo(expected);
    // 원본 입력은 그대로다.
    assertThat(KrakenScoreEngine.dealerScore(input, tables())).isEqualTo(before);
  }

  @ParameterizedTest(name = "{0} {1} {2} → {3} ▲{4}")
  @CsvSource({
    // 기준 캐릭터 1(l01) 장신구 연마 교체: 부위(같은 종류 순번) · 후보 코드(점수 옵션 두 개의 목표 등급, L 하·M 중·H 상·N 없음)
    // 귀걸이 1 무공%[중] 공%[하]
    "l01, POLISH, 귀걸이1, MM, 19.38",
    "l01, POLISH, 귀걸이1, HL, 20.31",
    "l01, POLISH, 귀걸이1, MH, 42.30",
    "l01, POLISH, 귀걸이1, HM, 41.45",
    "l01, POLISH, 귀걸이1, HH, 64.49",
    // 귀걸이 2 공%[중] 무공%[하] (코드 순서는 무공%·공%)
    "l01, POLISH, 귀걸이2, MM, 18.32",
    "l01, POLISH, 귀걸이2, LH, 22.95",
    "l01, POLISH, 귀걸이2, MH, 41.37",
    "l01, POLISH, 귀걸이2, HM, 40.17",
    "l01, POLISH, 귀걸이2, HH, 63.34",
    // 목걸이 추피[상] + 적주피 새로
    "l01, POLISH, 목걸이1, HL, 21.87",
    "l01, POLISH, 목걸이1, HM, 47.72",
    "l01, POLISH, 목걸이1, HH, 79.54",
    // 반지 치적[상] + 치피 새로
    "l01, POLISH, 반지1, HL, 15.84",
    "l01, POLISH, 반지1, HM, 34.57",
    "l01, POLISH, 반지1, HH, 57.61",
    "l01, POLISH, 반지2, HL, 14.18",
    "l01, POLISH, 반지2, HM, 32.90",
    "l01, POLISH, 반지2, HH, 55.93",
    // 기준 캐릭터 2(l05)
    "l05, POLISH, 반지2, HH, 20.16",
    "l05, POLISH, 반지1, HH, 18.87",
    "l05, POLISH, 귀걸이1, HH, 18.84",
    "l05, POLISH, 목걸이1, HH, 25.01",
    "l05, POLISH, 귀걸이2, HH, 17.53",
    // 어빌리티 스톤: 스톤 줄 순서의 두 각인 목표 레벨(l01 아드레날린·원한 = 3·2, l05 원한·저주받은 인형 = 2·3)
    "l01, STONE, 2:3, 0, 0.91",
    "l01, STONE, 1:4, 0, 0.91",
    "l01, STONE, 2:4, 0, 25.51",
    "l01, STONE, 3:3, 0, 49.82",
    "l01, STONE, 3:4, 0, 74.72",
    "l01, STONE, 4:4, 0, 99.33",
    "l05, STONE, 4:2, 0, 21.17",
    "l05, STONE, 3:3, 0, 42.85",
    "l05, STONE, 4:3, 0, 64.28",
    "l05, STONE, 4:4, 0, 85.84",
    // 깨달음 카르마 29 → 30
    "l01, KARMA, -, 30, 1.84",
    // 완갑 재련(목표 단계). 기준 캐릭터 2 표의 112.24는 '20강 → 21강 (권장 목표 25강)' 줄로,
    // 비교 기준 원본 계산상 20 → 25 값이다(20 → 21은 13.54).
    "l01, ARM_GUARD, -, 15, 37.02",
    "l05, ARM_GUARD, -, 21, 13.54",
    "l05, ARM_GUARD, -, 22, 28.65",
    "l05, ARM_GUARD, -, 25, 112.24",
  })
  void 레퍼런스_스펙업_추천의_새_후보_점수_차와_같다(
      String id, String kind, String target, String code, String expected) throws Exception {
    var doc =
        MAPPER.readTree(
            new ClassPathResource("kraken-score/" + id + ".json")
                .getContentAsString(StandardCharsets.UTF_8));
    var input = KrakenScoreInputMapper.from(doc.path("raw"));
    var loadout = ArmoryRawFixtures.loadout(doc.path("raw"));
    var scorer = new SpecUpKrakenScore(tables(), refine(), true);
    double before = KrakenScoreEngine.dealerScore(input, tables());
    var context = new SpecUpKrakenScore.Context(input, before, null);
    Double delta =
        switch (kind) {
          case "POLISH" -> {
            var rowId = "POLISH:" + accessoryIndex(loadout, target) + ":" + code;
            var row =
                SpecUpScoreCandidates.polish(input, loadout, polishValues()).stream()
                    .filter(planned -> planned.id().equals(rowId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(rowId + " 후보가 없습니다"));
            yield scorer.delta(context, row.kraken());
          }
          case "STONE" -> {
            var names = List.copyOf(SpecUpKrakenScore.stoneLevels(input).keySet());
            var levels = new LinkedHashMap<String, Integer>();
            var parts = target.split(":");
            levels.put(names.get(0), Integer.parseInt(parts[0]));
            levels.put(names.get(1), Integer.parseInt(parts[1]));
            yield scorer.delta(context, new SpecUpKrakenScore.Stone(levels));
          }
          case "KARMA" ->
              scorer.delta(context, new SpecUpKrakenScore.Karma(Integer.parseInt(code)));
          default -> {
            var item = SpecUpKrakenScore.armGuardItem(loadout);
            int level = Integer.parseInt(code);
            yield scorer.delta(
                context,
                loadout,
                new Change(
                    Kind.ARM_GUARD,
                    null,
                    null,
                    null,
                    level,
                    null,
                    null,
                    null,
                    scorer.armGuardGrade(item.grade(), level),
                    null,
                    null,
                    null,
                    null,
                    null));
          }
        };
    assertThat(delta).as("계산 가능").isNotNull();
    assertThat(SpecUpKrakenScore.round2(delta))
        .as("반올림 전 차 %s", delta)
        .isEqualByComparingTo(expected);
    assertThat(KrakenScoreEngine.dealerScore(input, tables())).isEqualTo(before);
  }

  @Test
  void 팔_보호구_목표_등급은_등급_끝을_넘을_때마다_이어서_올린다() throws Exception {
    var scorer = new SpecUpKrakenScore(tables(), refine(), true);
    // 영웅 0~10, 전설 10~15, 유물 15~20, 고대 20~25
    assertThat(scorer.armGuardGrade("전설", 15)).isEqualTo("전설");
    assertThat(scorer.armGuardGrade("전설", 16)).isEqualTo("유물");
    assertThat(scorer.armGuardGrade("전설", 21)).isEqualTo("고대");
    assertThat(scorer.armGuardGrade("영웅", 11)).isEqualTo("전설");
    assertThat(scorer.armGuardGrade("영웅", 25)).isEqualTo("고대");
    assertThat(scorer.armGuardGrade("고대", 25)).isEqualTo("고대");
    assertThat(scorer.armGuardGrade("고대", 26)).isNull();
    assertThat(scorer.armGuardGrade("모름", 15)).isNull();
  }

  @Test
  void 수치표와_다른_레벨_장신구는_연마_후보를_만들지_않는다() throws Exception {
    // 코드 리뷰 10-25: 점수에 안 쓰이는 줄만 있는 다른 레벨 장신구도 1680 표로 후보가 생겼다. 시뮬레이터와 같은 규칙으로 막는다.
    // 기준 캐릭터 1의 첫 귀걸이를 거래 제한 아이템 레벨 1540 장신구로 바꾸면 그 귀걸이 5줄만 빠진다.
    var l01 = context("l01");
    var loadout = loadout("l01");
    var equipment = new ArrayList<>(loadout.equipment());
    int first = -1;
    for (int i = 0; i < equipment.size(); i++)
      if (equipment.get(i) != null && "귀걸이".equals(equipment.get(i).type())) {
        first = i;
        break;
      }
    var item = equipment.get(first);
    var d = item.detail();
    equipment.set(
        first,
        new CharacterLoadout.Equipment(
            item.type(),
            item.name(),
            item.icon(),
            item.grade(),
            new CharacterLoadout.EquipmentDetail(
                d.refineLevel(),
                d.quality(),
                d.itemLevel(),
                d.tier(),
                d.arkPassivePoint(),
                d.effects(),
                d.stoneEngravings(),
                d.polishEffects(),
                d.braceletEffects(),
                d.titleLine(),
                List.of("거래 제한 아이템 레벨 1540"),
                d.refineExp(),
                d.extraLines(),
                d.advancedRefineLevel(),
                d.extraLineSections())));
    var lower =
        new CharacterLoadout(
            equipment,
            loadout.engravings(),
            loadout.gems(),
            loadout.cards(),
            loadout.arkPassive(),
            loadout.arkGrid(),
            loadout.skills(),
            loadout.avatars(),
            loadout.collectibles());
    var all = SpecUpScoreCandidates.polish(l01.input(), loadout, polishValues());
    var rows = SpecUpScoreCandidates.polish(l01.input(), lower, polishValues());
    assertThat(all).hasSize(19);
    int index = first;
    assertThat(rows).hasSize(14).noneMatch(row -> row.id().startsWith("POLISH:" + index + ":"));
  }

  @Test
  void 후보_목록_모양이_레퍼런스와_같다() throws Exception {
    // 기준 캐릭터 1: 연마 19줄(목걸이 3·귀걸이 5+5·반지 3+3), 스톤 6줄(배치는 점수가 더 오르는 쪽), 카르마 1줄(29 → 30).
    var l01 = context("l01");
    var polish = SpecUpScoreCandidates.polish(l01.input(), loadout("l01"), polishValues());
    assertThat(polish).hasSize(19);
    var stone = SpecUpScoreCandidates.stone(scorer(), l01, loadout("l01"));
    assertThat(stone)
        .extracting(SpecUpService.Planned::id)
        .containsExactly(
            "ABILITY_STONE:2:3",
            "ABILITY_STONE:1:4",
            "ABILITY_STONE:2:4",
            "ABILITY_STONE:3:3",
            "ABILITY_STONE:3:4",
            "ABILITY_STONE:4:4");
    assertThat(stone.getFirst().to()).isEqualTo("97돌 아드레날린 Lv.2 · 원한 Lv.3");
    assertThat(SpecUpScoreCandidates.karma(l01.input()))
        .extracting(SpecUpService.Planned::id)
        .containsExactly("KARMA_LEVEL:30");
    // 기준 캐릭터 2: 연마는 부위마다 한 줄(나머지 옵션이 모두 상), 스톤은 점수가 오르는 4줄(107·99·109·1010), 카르마는 이미 30.
    var l05 = context("l05");
    assertThat(SpecUpScoreCandidates.polish(l05.input(), loadout("l05"), polishValues()))
        .hasSize(5);
    assertThat(SpecUpScoreCandidates.stone(scorer(), l05, loadout("l05")))
        .extracting(SpecUpService.Planned::id)
        .containsExactly(
            "ABILITY_STONE:4:2", "ABILITY_STONE:3:3", "ABILITY_STONE:4:3", "ABILITY_STONE:4:4");
    assertThat(SpecUpScoreCandidates.karma(l05.input())).isEmpty();
  }

  @Test
  void 고정값_줄이_둘이면_둘_다_지금_줄로_보이고_목표에서_빠진다() throws Exception {
    // 목걸이: 추가 피해 상 + 고정값 공격력 상 + 고정값 무기 공격력 상(주옵션 하나뿐).
    var context = context("l01");
    var input = (ObjectNode) context.input().deepCopy();
    setOptions(input, "necklace", "추가 피해 +2.60%", "공격력 +390", "무기 공격력 +960");
    var withTwoFlats =
        new SpecUpKrakenScore.Context(input, KrakenScoreEngine.dealerScore(input, tables()), null);
    var rows = SpecUpScoreCandidates.polish(input, loadout("l01"), polishValues());
    var index = accessoryIndex(loadout("l01"), "목걸이1");
    var row =
        rows.stream()
            .filter(planned -> planned.id().equals("POLISH:" + index + ":HL"))
            .findFirst()
            .orElseThrow();
    assertThat(row.from()).isEqualTo("추가 피해 +2.60%(상) · 공격력 +390(상) · 무기 공격력 +960(상)");
    assertThat(row.to()).isEqualTo("추가 피해 +2.60%(상) · 적에게 주는 피해 +0.55%(하)");
    // Δ는 화면에 보인 그대로: 목표 = 두 옵션 줄만(고정값 두 줄이 모두 빠짐).
    var expected = (ObjectNode) input.deepCopy();
    setOptions(expected, "necklace", "추가 피해 +2.60%", "적에게 주는 피해 +0.55%");
    assertThat(scorer().delta(withTwoFlats, row.kraken()))
        .isEqualTo(KrakenScoreEngine.dealerScore(expected, tables()) - withTwoFlats.before());
  }

  @Test
  void 수치표_밖_고정값이_있는_부위는_후보를_만들지_않는다() throws Exception {
    // 다른 아이템 레벨 장신구의 고정값(공격력 +100은 1680 고대 표에 없음) → 그 부위 전체를 건너뜀(다른 부위는 그대로).
    var input = (ObjectNode) context("l01").input().deepCopy();
    setOptions(input, "necklace", "추가 피해 +2.60%", "공격력 +100", "낙인력 +2.15%");
    var rows = SpecUpScoreCandidates.polish(input, loadout("l01"), polishValues());
    var index = accessoryIndex(loadout("l01"), "목걸이1");
    assertThat(rows).noneMatch(planned -> planned.id().startsWith("POLISH:" + index + ":"));
    assertThat(rows).hasSize(16); // 귀걸이 5+5·반지 3+3
  }

  @Test
  void 두_옵션이_상이고_고정값_줄이_없으면_셋째_줄_후보는_상_두_줄뿐이다() throws Exception {
    // 목걸이 추가 피해 상 + 적에게 주는 피해 상 + 고정값이 아닌 줄 → 셋째 줄 후보는 무기 공격력 상·공격력 상 두 줄.
    var input = (ObjectNode) context("l01").input().deepCopy();
    setOptions(input, "necklace", "추가 피해 +2.60%", "적에게 주는 피해 +2.00%", "낙인력 +2.15%");
    var index = accessoryIndex(loadout("l01"), "목걸이1");
    assertThat(SpecUpScoreCandidates.polish(input, loadout("l01"), polishValues()))
        .extracting(SpecUpService.Planned::id)
        .filteredOn(id -> id.startsWith("POLISH:" + index + ":"))
        .containsExactly("POLISH:" + index + ":HHWH", "POLISH:" + index + ":HHAH");
    // 고정값 줄(공격력 하)이 있으면 그보다 높은 등급 모두(중·상 × 두 옵션 = 4줄).
    setOptions(input, "necklace", "추가 피해 +2.60%", "적에게 주는 피해 +2.00%", "공격력 +80");
    assertThat(SpecUpScoreCandidates.polish(input, loadout("l01"), polishValues()))
        .extracting(SpecUpService.Planned::id)
        .filteredOn(id -> id.startsWith("POLISH:" + index + ":"))
        .containsExactly(
            "POLISH:" + index + ":HHWM",
            "POLISH:" + index + ":HHWH",
            "POLISH:" + index + ":HHAM",
            "POLISH:" + index + ":HHAH");
  }

  private static void setOptions(ObjectNode input, String slot, String... lines) {
    var option =
        ((ObjectNode) input.path("armory").path("accessory").path(slot)).putArray("option");
    for (var line : lines) option.add(line);
  }

  private static SpecUpKrakenScore scorer() throws Exception {
    return new SpecUpKrakenScore(tables(), refine(), true);
  }

  private static SpecUpKrakenScore.Context context(String id) throws Exception {
    var input = KrakenScoreInputMapper.from(raw(id));
    return new SpecUpKrakenScore.Context(
        input, KrakenScoreEngine.dealerScore(input, tables()), null);
  }

  private static CharacterLoadout loadout(String id) throws Exception {
    return ArmoryRawFixtures.loadout(raw(id));
  }

  private static JsonNode raw(String id) throws Exception {
    return MAPPER
        .readTree(
            new ClassPathResource("kraken-score/" + id + ".json")
                .getContentAsString(StandardCharsets.UTF_8))
        .path("raw");
  }

  /** '귀걸이2' = 장비 목록에서 두 번째 귀걸이의 index. */
  private static int accessoryIndex(CharacterLoadout loadout, String target) {
    var type = target.substring(0, target.length() - 1);
    int nth = Integer.parseInt(target.substring(target.length() - 1));
    for (int i = 0; i < loadout.equipment().size(); i++)
      if (type.equals(loadout.equipment().get(i).type()) && --nth == 0) return i;
    throw new AssertionError(target + " 장신구가 없습니다");
  }

  private static CombatPowerPolishValues polishValues() throws Exception {
    try (var stream = new ClassPathResource("combat-power/polish-values.json").getInputStream()) {
      return MAPPER.readValue(stream, CombatPowerPolishValues.class);
    }
  }

  private static Change change(
      Kind kind, Integer slot, Integer index, Integer level, Integer refine) {
    return new Change(
        kind, slot, index, null, level, refine, null, null, null, null, null, null, null, null);
  }

  private static int damageGemSlot(CharacterLoadout loadout, String skill) {
    for (var effect : loadout.gems().effects().skills())
      if (skill.equals(effect.name())
          && !String.join(" ", effect.description()).contains("재사용 대기시간")) return effect.gemSlot();
    throw new AssertionError(skill + " 딜 보석이 없습니다");
  }

  private static int equipmentIndex(CharacterLoadout loadout, String type) {
    for (int i = 0; i < loadout.equipment().size(); i++)
      if (type.equals(loadout.equipment().get(i).type())) return i;
    throw new AssertionError(type + " 장비가 없습니다");
  }

  private static KrakenScoreEngine.Tables tables() throws Exception {
    var doc =
        MAPPER.readTree(
            new ClassPathResource("kraken-score/reference-tables.json")
                .getContentAsString(StandardCharsets.UTF_8));
    return new KrakenScoreEngine.Tables(doc.path("savedAt").asString(""), doc.path("tables"));
  }

  private static CombatPowerRefineModel.Coefficients refine() throws Exception {
    try (var stream =
        new ClassPathResource("combat-power/refine-coefficients.json").getInputStream()) {
      return MAPPER.readValue(stream, CombatPowerRefineModel.Coefficients.class);
    }
  }
}
