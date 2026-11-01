package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout.EffectGrade;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 재련 단계 모델(#319): 계산 검증 표본(ref-a-refine-model-2026-10-11.json)의 세 캐릭터를 공식 응답과 같은 형식의 입력으로 다시 만들어,
 * 기대 전투력을 ±0.01로 맞춘다.
 */
class CombatPowerRefineModelTest {
  private static CombatPowerRefineModel.Coefficients coefficients;

  @BeforeAll
  static void 계수() throws IOException {
    try (var input =
        new ClassPathResource("combat-power/refine-coefficients.json").getInputStream()) {
      coefficients = new JsonMapper().readValue(input, CombatPowerRefineModel.Coefficients.class);
    }
  }

  @Test
  void 기준_캐릭터_전율_무기와_투구_단계() {
    // B 188,163, q 7.45%, F 2,030, W 고정 222,947(무기 208,130 + 팔 보호구 14,817), 무공% 1.80 + 0.80 + 카르마
    // 29 × 0.1.
    var loadout =
        loadout(19, 19, "운명의 전율 검", "208130", "2030", "14817", List.of("+1.80%", "+0.80%"), 29);
    var model =
        CombatPowerRefineModel.of(coefficients, loadout, stats("188163"), new BigDecimal("7.45"));
    assertThat(model.unsupportedReason()).isNull();
    assertThat(after("3345.24", model, Map.of(1, 20))).isEqualByComparingTo("3352.32");
    assertThat(after("3345.24", model, Map.of(0, 20))).isEqualByComparingTo("3383.60");
    assertThat(after("3345.24", model, Map.of(0, 25)).subtract(new BigDecimal("3583.14")).abs())
        .isLessThanOrEqualTo(new BigDecimal("0.01"));
    assertThat(after("3345.24", model, Map.of(0, 16))).isEqualByComparingTo("3233.15");
    // 팔찌 "민첩 +15360"(S 항, 계산 검증 #332 회신): 목걸이 같은 수치와 같은 ×1.010768.
    assertThat(
            model
                .ratio(Map.of(), BigDecimal.ZERO, new BigDecimal("15360"))
                .setScale(6, RoundingMode.HALF_UP))
        .isEqualByComparingTo("1.010768");
    // 연마 무공 줄 교체(L31-b, 계산 검증 10-13 04:49): 귀걸이 무공 1.80% 줄을 0.80%·3.00%로 바꾼 비교 기준 B 시뮬레이터 비율
    // 0.995316·1.005590과 ±0.00002 안이다.
    assertThat(
            model
                .ratioFull(
                    Map.of(), BigDecimal.ZERO, new BigDecimal("-0.010"), BigDecimal.ZERO, null)
                .subtract(new BigDecimal("0.995316"))
                .abs())
        .isLessThanOrEqualTo(new BigDecimal("0.00002"));
    assertThat(
            model
                .ratioFull(
                    Map.of(), BigDecimal.ZERO, new BigDecimal("0.012"), BigDecimal.ZERO, null)
                .subtract(new BigDecimal("1.005590"))
                .abs())
        .isLessThanOrEqualTo(new BigDecimal("0.00002"));
    // 무공% 변화가 없으면 기존 식과 같다.
    assertThat(
            model.ratioFull(
                Map.of(1, new CombatPowerRefineModel.Target(20, 0)),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null))
        .isEqualByComparingTo(
            model.ratioTargets(
                Map.of(1, new CombatPowerRefineModel.Target(20, 0)),
                BigDecimal.ZERO,
                BigDecimal.ZERO));
  }

  @Test
  void 혼돈의_별_코어_무기는_무기_공격력_고정값과_퍼센트로_W에_들어간다() {
    // 코드 리뷰·계산 검증 10-25 P0-3: 무기 코어가 있어도 Δ가 같던 문제. 고대 17P = +5,200, +3%.
    var base =
        loadout(19, 19, "운명의 전율 검", "208130", "2030", "14817", List.of("+1.80%", "+0.80%"), 29);
    var withCore =
        new CharacterLoadout(
            base.equipment(),
            base.engravings(),
            base.gems(),
            base.cards(),
            base.arkPassive(),
            new CharacterLoadout.ArkGrid(
                List.of(
                    new CharacterLoadout.GridSlot(0, null, "혼돈의 별 코어 : 무기", 17, "고대", List.of())),
                List.of()),
            base.skills(),
            base.avatars(),
            base.collectibles());
    var none =
        CombatPowerRefineModel.of(coefficients, base, stats("188163"), new BigDecimal("7.45"));
    var core =
        CombatPowerRefineModel.of(coefficients, withCore, stats("188163"), new BigDecimal("7.45"));
    assertThat(core.unsupportedReason()).isNull();
    // W = (무기 208,130 + 팔 보호구 14,817 + 코어 5,200) × (1 + 무공% 2.6 + 카르마 2.9 + 코어 3).
    assertThat(none.w())
        .isEqualByComparingTo(new BigDecimal("222947").multiply(new BigDecimal("1.055")));
    assertThat(core.w())
        .isEqualByComparingTo(new BigDecimal("228147").multiply(new BigDecimal("1.085")));
    var afterNone = after("3345.24", none, Map.of(0, 20));
    var afterCore = after("3345.24", core, Map.of(0, 20));
    assertThat(afterNone).isEqualByComparingTo("3383.60");
    // 코어가 W를 키우면 같은 무기 단계 차이의 비중이 줄어 Δ가 작아진다(코드 리뷰 손계산 약 3382.73과 같음).
    assertThat(afterCore).isEqualByComparingTo("3382.73");
  }

  @Test
  void 무기_코어는_포인트_구간과_등급별_값이고_모르는_등급은_계산하지_않는다() {
    assertThat(core(15, "고대").flat()).isEqualByComparingTo("1300");
    assertThat(core(15, "고대").pct()).isEqualByComparingTo("0.75");
    assertThat(core(20, "유물").flat()).isEqualByComparingTo("3900");
    assertThat(core(20, "유물").pct()).isEqualByComparingTo("2.94");
    assertThat(core(12, "유물").pct()).isZero();
    assertThat(core(12, "유물").flat()).isEqualByComparingTo("1300");
    assertThat(core(9, "고대").flat()).isZero();
    assertThat(core(17, "전설").reason()).contains("전설");
    assertThat(core(null, "고대").reason()).isNotNull();
    // 이름을 읽지 못한 코어는 무기 코어인지 몰라 0으로 두지 않는다(코드 리뷰 10-25 P2).
    var unnamed =
        CombatPowerRefineModel.weaponCore(
            new CharacterLoadout(
                List.of(),
                null,
                null,
                null,
                null,
                new CharacterLoadout.ArkGrid(
                    List.of(new CharacterLoadout.GridSlot(0, null, null, 17, "고대", List.of())),
                    List.of()),
                List.of(),
                List.of(),
                List.of()));
    assertThat(unnamed.reason()).contains("코어 이름");
  }

  private static CombatPowerRefineModel.WeaponCore core(Integer point, String grade) {
    return CombatPowerRefineModel.weaponCore(
        new CharacterLoadout(
            List.of(),
            null,
            null,
            null,
            null,
            new CharacterLoadout.ArkGrid(
                List.of(
                    new CharacterLoadout.GridSlot(
                        0, null, "혼돈의 별 코어 : 무기", point, grade, List.of())),
                List.of()),
            List.of(),
            List.of(),
            List.of()));
  }

  @Test
  void 에스더_무기는_계산하지_않고_전율_방어구는_계산한다() {
    // c01: B 267,396, q 17.7%, F 9,050, W 고정 283,652(에스더 259,554 + 22,940 + 장신구 960 + 195 + 3),
    // 무공% 3 + 3 + 3.0.
    var c01 =
        loadout(
            10,
            25,
            "에스더 테스트 검",
            "259554",
            "9050",
            "22940",
            List.of("+960", "+195", "+3", "+3.00%", "+3.00%"),
            30);
    var model =
        CombatPowerRefineModel.of(coefficients, c01, stats("267396"), new BigDecimal("17.7"));
    assertThat(after("11028.4", model, Map.of(1, 24))).isEqualByComparingTo("11007.13");
    assertThat(after("11028.4", model, Map.of(1, 19))).isEqualByComparingTo("10907.74");
    assertThat(model.cannotSet(0, 11)).contains("전율·업화 장비만");
    // c02: W 고정 285,374(장신구 960 × 3).
    var manbae =
        loadout(
            10,
            25,
            "에스더 테스트 검",
            "259554",
            "9050",
            "22940",
            List.of("+960", "+960", "+960", "+3.00%", "+3.00%"),
            30);
    var other =
        CombatPowerRefineModel.of(coefficients, manbae, stats("267757"), new BigDecimal("17.7"));
    assertThat(after("10724.16", other, Map.of(1, 24))).isEqualByComparingTo("10703.41");
  }

  @Test
  void 입력을_읽지_못하면_계산하지_않는다() {
    var loadout = loadout(19, 19, "운명의 전율 검", "208130", "2030", "14817", List.of(), 29);
    assertThat(
            CombatPowerRefineModel.of(coefficients, loadout, List.of(), new BigDecimal("7.45"))
                .unsupportedReason())
        .contains("기본 공격력");
    assertThat(
            CombatPowerRefineModel.of(coefficients, loadout, stats("188163"), null)
                .unsupportedReason())
        .contains("기본 공격력 % 합");
    var model =
        CombatPowerRefineModel.of(coefficients, loadout, stats("188163"), new BigDecimal("7.45"));
    assertThat(model.cannotSet(1, 26)).contains("26단계의 수치를 아직 확인하지 않아");
  }

  @Test
  void 업화_수치는_공식_표_T에_상급_보너스를_곱해_버린다() {
    // 공식 아이템 사전 T(5 × 일반 + 상급) × (1 + 30단계 2% + 40단계 3%)를 버림. #333 표본 투구 칸은 모두 정확히 같다(전에는 보간으로 ±1).
    var helmet =
        Map.of(
            "11/2", 59632, "12/3", 61379, "15/3", 66175, "17/4", 70020, "20/1", 74560, "21/2",
            76974);
    for (var entry : helmet.entrySet()) {
      var cell = entry.getKey().split("/");
      assertThat(
              stat(
                  "투구",
                  CombatPowerRefineModel.Family.UPGRADE,
                  Integer.parseInt(cell[0]),
                  Integer.parseInt(cell[1])))
          .as(entry.getKey())
          .isEqualByComparingTo(String.valueOf(entry.getValue()));
    }
    // 무기 +17·상급 40 = T(125) × 1.05, +11·상급 0 = 102,384(공식 관측). 전에는 확인하지 않았던 +11·상급 1도 공식 표에 있다.
    assertThat(stat("무기", CombatPowerRefineModel.Family.UPGRADE, 17, 40))
        .isEqualByComparingTo("154350");
    assertThat(stat("무기", CombatPowerRefineModel.Family.UPGRADE, 11, 0))
        .isEqualByComparingTo("102384");
    assertThat(stat("무기", CombatPowerRefineModel.Family.UPGRADE, 11, 1))
        .isEqualByComparingTo("102863");
    // 버림(반올림이면 69,960·122,484): 투구 +11·상급 30, 무기 +11·상급 32.
    assertThat(stat("투구", CombatPowerRefineModel.Family.UPGRADE, 11, 30))
        .isEqualByComparingTo("69959");
    assertThat(stat("무기", CombatPowerRefineModel.Family.UPGRADE, 11, 32))
        .isEqualByComparingTo("122483");
    // 지수 140~165(상급 +23~+25 등)는 아직 공식 표를 확인하는 중이라 계산하지 않는다.
    assertThat(stat("투구", CombatPowerRefineModel.Family.UPGRADE, 22, 32)).isNull();
    assertThat(stat("투구", CombatPowerRefineModel.Family.UPGRADE, 24, 38)).isNull();
    // 전율은 상급 재련 수치가 없다.
    assertThat(stat("투구", CombatPowerRefineModel.Family.JEONYUL, 19, 1)).isNull();
  }

  @Test
  void 업화_방어구는_일반과_상급_재련을_계산하고_전율_상급은_계산하지_않는다() {
    var loadout =
        loadout(19, 19, "운명의 전율 검", "208130", "2030", "14817", List.of("+1.80%", "+0.80%"), 29);
    var equipment = new ArrayList<>(loadout.equipment());
    // 투구를 업화 +21·상급 30(지수 135, 공식 표 안)으로 바꾼다.
    equipment.set(
        1,
        new CharacterLoadout.Equipment(
            "투구",
            "+21 운명의 업화 모자",
            null,
            "고대",
            new CharacterLoadout.EquipmentDetail(
                21,
                null,
                null,
                4,
                null,
                List.of(new CharacterLoadout.EffectGroup("기본 효과", List.of("힘 +100"))),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                30)));
    var upgraded =
        new CharacterLoadout(
            equipment,
            null,
            null,
            null,
            loadout.arkPassive(),
            null,
            List.of(),
            loadout.avatars(),
            List.of());
    var model =
        CombatPowerRefineModel.of(coefficients, upgraded, stats("188163"), new BigDecimal("7.45"));
    assertThat(model.cannotSetAdvanced(1, 34)).isNull();
    assertThat(model.cannotSet(1, 20)).isNull();
    // 지수 140 이상(+21·상급 35 = 140)은 공식 표를 확인하는 중이라 계산하지 않는다.
    assertThat(model.cannotSetAdvanced(1, 35)).isNotNull();
    var up =
        model.ratioTargets(
            Map.of(1, new CombatPowerRefineModel.Target(21, 34)), BigDecimal.ZERO, BigDecimal.ZERO);
    assertThat(up).isGreaterThan(BigDecimal.ONE);
    // 상급 30 → 34 수치 차이를 S 항에 그대로 쓴다. 지금과 같은 칸이면 비율 1.
    var same =
        model.ratioTargets(
            Map.of(1, new CombatPowerRefineModel.Target(21, 30)), BigDecimal.ZERO, BigDecimal.ZERO);
    assertThat(same).isEqualByComparingTo("1");
    // 전율 상의(순번 2)의 상급 재련은 계산하지 않는다.
    // E21: 전율 장비에는 상급 재련이 없다(공식 가이드·툴팁, 검증 표본 official-jeonyul-refine-stats).
    assertThat(model.cannotSetAdvanced(2, 10)).isEqualTo("전율 장비에는 상급 재련이 없습니다.");
  }

  @Test
  void 팔_보호구_단계와_등급을_바꾸면_표본_7건과_같다() {
    // #341 표본(ref-a-bracer-armguard-2026-10-11.json): 기준 캐릭터 완갑 +13 전설, q 7.45%(보석 4.95 + 스톤 1.50 +
    // 완갑 1.00).
    var base =
        loadout(19, 19, "운명의 전율 검", "208130", "2030", "14817", List.of("+1.80%", "+0.80%"), 29);
    var equipment = new ArrayList<>(base.equipment());
    for (int i = 0; i < equipment.size(); i++)
      if ("완갑".equals(equipment.get(i).type())) {
        var guard = equipment.get(i);
        equipment.set(
            i,
            new CharacterLoadout.Equipment(guard.type(), guard.name(), null, "전설", guard.detail()));
      }
    var loadout =
        new CharacterLoadout(
            equipment,
            null,
            null,
            null,
            base.arkPassive(),
            null,
            List.of(),
            base.avatars(),
            List.of());
    // 완갑 단계는 일반 재련 단계 칸(refineLevel)에서 읽는다.
    var model =
        CombatPowerRefineModel.of(
            coefficients, withGuardLevel(loadout, 13), stats("188163"), new BigDecimal("7.45"));
    assertThat(model.armGuardReason()).isNull();
    var cases =
        Map.of(
            "14/전설", "1.004434958",
            "25/전설", "1.081164192",
            "15/유물", "1.023350492",
            "20/고대", "1.064382077",
            "25/고대", "1.101288248",
            "10/영웅", "0.977893544",
            "15/전설", "1.013914368");
    for (var entry : cases.entrySet()) {
      var key = entry.getKey().split("/");
      var target = new CombatPowerRefineModel.ArmGuard(Integer.parseInt(key[0]), key[1]);
      assertThat(model.cannotSetArmGuard(target.level(), target.grade()))
          .as(entry.getKey())
          .isNull();
      var ratio =
          model.ratioFull(Map.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, target);
      var q = new BigDecimal("1.0745");
      ratio =
          ratio
              .multiply(q.add(model.armGuardPctDelta(target)))
              .divide(q, java.math.MathContext.DECIMAL64);
      // 전투력 3,345.24 기준 ±0.01 → 비율 차이 3e-6 이하.
      assertThat(ratio.subtract(new BigDecimal(entry.getValue())).abs())
          .as(entry.getKey())
          .isLessThan(new BigDecimal("0.000003"));
    }
    // 5단계 주기로 채운 단계(+4)는 계산하지 않는다.
    assertThat(model.cannotSetArmGuard(4, "영웅")).contains("아직 확인하지 않아");
  }

  /** 완갑 장비의 refineLevel을 level로 바꾼 로드아웃(테스트 도우미). */
  private static CharacterLoadout withGuardLevel(CharacterLoadout loadout, int level) {
    var equipment = new ArrayList<CharacterLoadout.Equipment>();
    for (var item : loadout.equipment()) {
      if (!"완갑".equals(item.type())) {
        equipment.add(item);
        continue;
      }
      var detail = item.detail();
      equipment.add(
          new CharacterLoadout.Equipment(
              item.type(),
              item.name(),
              null,
              item.grade(),
              new CharacterLoadout.EquipmentDetail(
                  level, null, null, 4, null, detail.effects(), List.of(), List.of(), List.of())));
    }
    return new CharacterLoadout(
        equipment,
        null,
        null,
        null,
        loadout.arkPassive(),
        null,
        List.of(),
        loadout.avatars(),
        List.of());
  }

  private static BigDecimal stat(
      String type, CombatPowerRefineModel.Family family, int level, int advanced) {
    return CombatPowerRefineModel.stat(coefficients, type, family, level, advanced);
  }

  private static BigDecimal after(
      String combatPower, CombatPowerRefineModel.Model model, Map<Integer, Integer> levels) {
    return new BigDecimal(combatPower)
        .multiply(model.ratio(levels))
        .setScale(2, RoundingMode.HALF_UP);
  }

  private static List<CharacterProfile.Stat> stats(String baseAttack) {
    return List.of(
        new CharacterProfile.Stat(
            "공격력",
            "0",
            List.of(
                "적에게 주는 피해를 계산할 때 기준이 되는 값입니다.",
                "힘, 민첩, 지능과 무기 공격력을 기반으로 증가한 기본 공격력은 " + baseAttack + " 입니다.")));
  }

  /** 장비 순번: 0 무기, 1 투구, 2 상의, 3 하의, 4 장갑, 5 어깨, 6 완갑, 7~ 장신구(연마 무기 공격력). */
  private static CharacterLoadout loadout(
      int weaponLevel,
      int armorLevel,
      String weaponName,
      String weaponAttack,
      String armGuardBase,
      String armGuardWeapon,
      List<String> polishWeaponAttack,
      int karma) {
    var equipment = new ArrayList<CharacterLoadout.Equipment>();
    equipment.add(
        item(
            "무기",
            "+" + weaponLevel + " " + weaponName,
            weaponLevel,
            List.of("무기 공격력 +" + weaponAttack)));
    for (var part : List.of("투구", "상의", "하의", "장갑", "어깨"))
      equipment.add(
          item(part, "+" + armorLevel + " 운명의 전율 " + part, armorLevel, List.of("힘 +100")));
    equipment.add(
        item(
            "완갑",
            "+13 운명의 전율 완갑",
            null,
            List.of("무기 공격력 +" + armGuardWeapon, "기본 공격력 +" + armGuardBase)));
    for (var value : polishWeaponAttack)
      equipment.add(
          new CharacterLoadout.Equipment(
              "귀걸이",
              "테스트 귀걸이",
              null,
              "고대",
              new CharacterLoadout.EquipmentDetail(
                  null,
                  80,
                  null,
                  4,
                  null,
                  List.of(),
                  List.of(),
                  List.of(new CharacterLoadout.PolishEffect("무기 공격력", value, EffectGrade.MID)),
                  List.of())));
    var avatars = new ArrayList<CharacterLoadout.Avatar>();
    for (var type : List.of("무기 아바타", "머리 아바타", "상의 아바타", "하의 아바타")) {
      avatars.add(
          new CharacterLoadout.Avatar(type, "전설 " + type, null, "전설", false, true, List.of()));
      avatars.add(
          new CharacterLoadout.Avatar(type, "영웅 " + type, null, "영웅", false, false, List.of()));
    }
    var arkPassive =
        new CharacterLoadout.ArkPassive(
            "깨달음",
            true,
            List.of(new CharacterLoadout.PassivePoint("깨달음", 101, "6랭크 " + karma + "레벨")),
            List.of());
    return new CharacterLoadout(
        equipment, null, null, null, arkPassive, null, List.of(), avatars, List.of());
  }

  private static CharacterLoadout.Equipment item(
      String type, String name, Integer refine, List<String> base) {
    return new CharacterLoadout.Equipment(
        type,
        name,
        null,
        "고대",
        new CharacterLoadout.EquipmentDetail(
            refine,
            null,
            null,
            4,
            null,
            List.of(new CharacterLoadout.EffectGroup("기본 효과", base)),
            List.of(),
            List.of(),
            List.of()));
  }

  @Test
  void 아바타_주스탯은_공식_툴팁_퍼센트를_읽고_부위마다_큰_값을_더한다() throws IOException {
    // E29 표본(official-avatar-stat-2026-10-12.json): 한벌이 아닌 34줄은 전설 2%·영웅 1%로 표와 같다.
    var sample =
        new JsonMapper()
            .readTree(
                new ClassPathResource(
                        "verification/combat-power/official-avatar-stat-2026-10-12.json")
                    .getInputStream());
    var byId = new java.util.LinkedHashMap<String, List<CharacterLoadout.Avatar>>();
    var expected = new java.util.LinkedHashMap<String, Map<String, BigDecimal>>();
    for (var row : sample.path("rows")) {
      var id = row.path("id").asText();
      var type = row.path("type").asText();
      var pct = row.path("officialStatPct").decimalValue();
      byId.computeIfAbsent(id, key -> new ArrayList<>())
          .add(avatar(type, row.path("grade").asText(), row.path("inner").asBoolean(), pct));
      var slots = expected.computeIfAbsent(id, key -> new java.util.HashMap<>());
      if (row.path("onePiece").asBoolean()) {
        var half = pct.divide(BigDecimal.TWO);
        slots.merge("상의 아바타", half, BigDecimal::max);
        slots.merge("하의 아바타", half, BigDecimal::max);
      } else slots.merge(type, pct, BigDecimal::max);
    }
    for (var entry : byId.entrySet()) {
      var stat = CombatPowerRefineModel.avatarStat(coefficients, entry.getValue());
      var sum =
          expected.get(entry.getKey()).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      assertThat(stat.sum().multiply(BigDecimal.valueOf(100)))
          .as(entry.getKey())
          .isEqualByComparingTo(sum);
      assertThat(stat.reason()).isNull();
    }
  }

  @Test
  void 한벌_아바타는_상의_칸의_퍼센트를_상의와_하의에_나눈다() {
    // 한벌만(영웅, 공식 2%): 상의 1% + 하의 1% = 2%. 예전에는 상의 칸을 1%로 보고 하의를 놓쳤다.
    var onePiece = avatar("상의 아바타", "영웅", false, new BigDecimal("2"));
    assertThat(CombatPowerRefineModel.avatarStat(coefficients, List.of(onePiece)).sum())
        .isEqualByComparingTo("0.02");
    // 한벌 + 안쪽 전설 상의·하의(각 2%): 부위마다 큰 값 → 4%.
    assertThat(
            CombatPowerRefineModel.avatarStat(
                    coefficients,
                    List.of(
                        onePiece,
                        avatar("상의 아바타", "전설", true, new BigDecimal("2")),
                        avatar("하의 아바타", "전설", true, new BigDecimal("2"))))
                .sum())
        .isEqualByComparingTo("0.04");
    // 한벌 + 안쪽 전설 상의만: 상의 2% + 하의 1%(한벌 절반) = 3%.
    assertThat(
            CombatPowerRefineModel.avatarStat(
                    coefficients,
                    List.of(onePiece, avatar("상의 아바타", "전설", true, new BigDecimal("2"))))
                .sum())
        .isEqualByComparingTo("0.03");
    // 공식 줄이 없으면 등급 표(영웅 1%), 표에도 없는 등급이면 이유 문장.
    var noLine = new CharacterLoadout.Avatar("무기 아바타", "무기", null, "영웅", false, false, List.of());
    assertThat(CombatPowerRefineModel.avatarStat(coefficients, List.of(noLine)).sum())
        .isEqualByComparingTo("0.01");
    var unknown = new CharacterLoadout.Avatar("무기 아바타", "무기", null, "희귀", false, false, List.of());
    assertThat(CombatPowerRefineModel.avatarStat(coefficients, List.of(unknown)).reason())
        .contains("희귀");
  }

  private static CharacterLoadout.Avatar avatar(
      String type, String grade, boolean inner, BigDecimal pct) {
    return new CharacterLoadout.Avatar(
        type,
        grade + " " + type,
        null,
        grade,
        false,
        inner,
        List.of("민첩 +" + pct.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%"));
  }

  @Test
  void 전율_재련_단계표는_공식_아이템_사전_0에서_25단계와_같다() throws IOException {
    // E20: 공식 아이템 사전 6부위 × 0~25(검증 표본). 예전에는 11~25만 있어 0~10단계 변화는 계산하지 않았다.
    var sample =
        new JsonMapper()
            .readTree(
                new ClassPathResource(
                        "verification/combat-power/official-jeonyul-refine-stats-2026-10-12.json")
                    .getInputStream())
            .path("table");
    int cells = 0;
    for (var part : sample.propertyNames())
      for (int level = 0; level <= 25; level++) {
        assertThat(
                CombatPowerRefineModel.stat(
                    coefficients, part, CombatPowerRefineModel.Family.JEONYUL, level, 0))
            .as(part + " " + level)
            .isEqualByComparingTo(sample.path(part).path(String.valueOf(level)).decimalValue());
        cells++;
      }
    assertThat(cells).isEqualTo(6 * 26);
  }
}
