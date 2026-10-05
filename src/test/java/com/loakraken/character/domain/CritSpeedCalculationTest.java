package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CritSpeedCalculation.Assumptions;
import com.loakraken.character.domain.CritSpeedCalculation.Axis;
import com.loakraken.character.domain.CritSpeedCalculation.Category;
import com.loakraken.character.domain.CritSpeedCalculation.Kind;
import com.loakraken.character.domain.CritSpeedCalculation.NodeSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CritSpeedCalculationTest {
  // 2026-10-05 공식 원문 문구(언새늘·랭킹 캐릭터)에서 발췌한 효과 문장
  private static final String ADRENALINE =
      "이동기 및 기본공격을 제외한 스킬 사용 후 6초 동안 공격력이 1.73% 증가하며 (최대 6중첩) 해당 효과가 최대 중첩 도달 시 치명타 적중률이 추가로 18.50% 증가한다.";
  private static final String KEEN = "치명타 적중률이 8.0% 증가하고, 진화형 피해가 10.0% 증가합니다.";
  private static final String STRIKE = "치명타 적중률이 20.0% 증가하고, 방향성 공격 스킬의 치명타 피해가 32.0% 증가합니다.";
  private static final String MASTER =
      "받는 피해가 4.0% 감소하며, 이동기 및 기상기를 제외한 스킬 사용시 10초간 '달인' 효과를 얻습니다. 달인 : 치명타 적중률 +1.4% / 추가 피해 +1.7%, 최대 5중첩";
  private static final String BLUNT_THORN =
      "진화형 피해가 15.0% 증가합니다. 치명타가 발생할 확률이 최대 80.0% 로 제한됩니다. 공격 시, 초과한 모든 치명타가 발생할 확률의 150.0%가 진화형 피해로 전환됩니다. 이 노드에 의한 진화형 피해는 최대 75.0%까지 적용됩니다.";
  private static final String SUPERSONIC =
      "공격 적중 시, 공격 속도 및 이동 속도 증가량의 10.0% 만큼 진화형 피해가 증가합니다. 공격 및 이동 속도가 모두 상한을 초과했다면, 진화형 피해가 추가로 8.0% 증가하며, 상한을 초과한 속도 증가량의 30.0%만큼 적중 시 진화형 피해가 증가합니다. 이 노드에 의한 진화형 피해는 최대 24.0%까지 적용됩니다.";

  @Test
  void 언새늘_세팅은_로아랩과_같은_치적과_음돌_적정_신속을_낸다() {
    var result =
        CritSpeedCalculation.calculate(
            profile("576", "77"),
            loadout(
                List.of(
                    node("진화 2티어 예리한 감각 Lv.2", KEEN),
                    node("진화 3티어 일격 Lv.2", STRIKE),
                    node("진화 4티어 달인 Lv.1", MASTER))),
            new Assumptions(
                true, false, 0, null, null, null, new BigDecimal("14"), new BigDecimal("14")));

    var crit = result.crit();
    // 치명 20.61 + 아드레날린 18.5 + 예리 8 + 일격 20 + 달인 7 + 반지 1.55×2 + 백어택 10
    assertThat(crit.observedRate()).isEqualByComparingTo("77.21");
    assertThat(crit.assumedRate()).isEqualByComparingTo("10");
    assertThat(crit.totalRate()).isEqualByComparingTo("87.21");
    assertThat(crit.critStat()).isEqualTo(576);
    var hundred = crit.targets().get(0);
    assertThat(hundred.key()).isEqualTo("CRIT_100");
    assertThat(hundred.requiredCritStat()).isEqualTo(934);
    assertThat(hundred.requiredAdditionalCritStat()).isEqualTo(358);
    var thornTarget = crit.targets().get(1);
    assertThat(thornTarget.targetRate()).isEqualByComparingTo("120");
    assertThat(thornTarget.requiredAdditionalCritStat()).isEqualTo(917);
    assertThat(crit.bluntThorn().source()).isEqualTo(NodeSource.REFERENCE_LEVEL_2);
    assertThat(crit.bluntThorn().evolutionDamage()).isEqualByComparingTo("25.82");
    assertThat(crit.effectiveRate()).isEqualByComparingTo("87.21");

    var speed = result.speed();
    assertThat(speed.attack().total()).isEqualByComparingTo("15.32");
    assertThat(speed.supersonic().optimalSpeed()).isEqualByComparingTo("53.33");
    assertThat(speed.supersonic().requiredSwiftness()).isEqualTo(2290);
    assertThat(speed.supersonic().requiredAdditionalSwiftness()).isEqualTo(2213);
    assertThat(speed.supersonic().evolutionDamage()).isEqualByComparingTo("3.06");

    assertThat(result.sources())
        .filteredOn(
            source -> source.kind() == Kind.OBSERVED && source.category() == Category.ARK_PASSIVE)
        .extracting(source -> source.value().stripTrailingZeros().toPlainString())
        .containsExactly("8", "20", "7");
  }

  @Test
  void 감소_효과는_띄어쓰기_없는_문구도_음수로_더한다() {
    var sources = new ArrayList<CritSpeedCalculation.Source>();
    CritSpeedCalculation.scan(
        Category.ENGRAVING,
        "질량 증가",
        "공격속도가 10.00% 감소하지만, 적에게 주는 피해가 19.00% 증가한다. 이동속도 증가량의 48.00% 만큼 피해가 증가한다.",
        sources,
        true);
    assertThat(sources).hasSize(1);
    assertThat(sources.getFirst().axis()).isEqualTo(Axis.ATTACK_SPEED);
    assertThat(sources.getFirst().value()).isEqualByComparingTo("-10");
  }

  @Test
  void 신속_스탯별_표는_현재값과_최적값_행을_포함하고_신속_외_출처를_유지한다() {
    var result =
        CritSpeedCalculation.calculate(
            profile("576", "77"),
            loadout(List.of()),
            new Assumptions(
                false, false, 0, null, null, null, new BigDecimal("14"), new BigDecimal("14")));
    var speed = result.speed();
    assertThat(speed.swiftnessPerPercent()).isEqualByComparingTo("58.22");
    var table = speed.swiftnessTable();
    assertThat(table.getFirst().swiftness()).isZero();
    assertThat(table.getFirst().attack()).isEqualByComparingTo("14");
    assertThat(table).anyMatch(row -> row.current() && row.swiftness() == 77);
    var optimal =
        table.stream().filter(CritSpeedCalculation.SwiftnessRow::optimal).findFirst().orElseThrow();
    assertThat(optimal.swiftness()).isEqualTo(2290);
    assertThat(optimal.attack()).isEqualByComparingTo("53.33");
    assertThat(optimal.attackEffective()).isEqualByComparingTo("40");
    assertThat(optimal.supersonicEvolutionDamage()).isEqualByComparingTo("24");
    assertThat(table.getLast().swiftness()).isEqualTo(2600);
    assertThat(table).extracting(CritSpeedCalculation.SwiftnessRow::swiftness).isSorted();
  }

  @Test
  void 장착한_뭉툭한_가시와_음속_돌파는_공식_문구의_수치로_계산한다() {
    var result =
        CritSpeedCalculation.calculate(
            profile("1800", "1794"),
            loadout(
                List.of(
                    node("진화 5티어 뭉툭한 가시 Lv.2", BLUNT_THORN),
                    node("진화 5티어 음속 돌파 Lv.2", SUPERSONIC))),
            new Assumptions(
                false,
                false,
                0,
                null,
                null,
                new BigDecimal("50"),
                new BigDecimal("30"),
                new BigDecimal("30")));

    var thorn = result.crit().bluntThorn();
    assertThat(thorn.source()).isEqualTo(NodeSource.EQUIPPED_NODE);
    assertThat(thorn.rule().level()).isEqualTo(2);
    // 치명 64.42 + 아드레날린 18.5 + 반지 3.1 + 가정 50 = 136.02 → 상한 80, 15 + 56.02×1.5 = 99.03 → 최대 75
    assertThat(result.crit().totalRate()).isEqualByComparingTo("136.02");
    assertThat(result.crit().effectiveRate()).isEqualByComparingTo("80");
    assertThat(thorn.evolutionDamage()).isEqualByComparingTo("75");
    assertThat(result.crit().targets().get(1).reached()).isTrue();
    assertThat(result.crit().targets().get(1).requiredAdditionalCritStat()).isZero();
    // 노드 자체 문구는 치적·속도로 합산하지 않는다.
    assertThat(result.sources()).noneMatch(source -> source.category() == Category.ARK_PASSIVE);

    var supersonic = result.speed().supersonic();
    assertThat(supersonic.source()).isEqualTo(NodeSource.EQUIPPED_NODE);
    // 공이속 각 30.81 + 30 = 60.81 → 0.1×80 + 8 + 0.3×41.62 = 28.49 → 최대 24
    assertThat(result.speed().attack().total()).isEqualByComparingTo("60.81");
    assertThat(result.speed().attack().effective()).isEqualByComparingTo("40");
    assertThat(supersonic.evolutionDamage()).isEqualByComparingTo("24");
    assertThat(supersonic.requiredAdditionalSwiftness()).isZero();
  }

  @Test
  void 스킬_한정_효과는_건너뛰고_공격_및_이동_속도는_양쪽에_더한다() {
    var sources = new ArrayList<CritSpeedCalculation.Source>();
    CritSpeedCalculation.scan(
        Category.ARK_PASSIVE,
        "노드",
        "블레이드 버스트의 치명타 적중률이 30.0% 증가한다. 공격 및 이동 속도가 5.0% 증가한다. 공격 속도가 4.0% 증가합니다.",
        sources,
        true);
    assertThat(sources).noneMatch(source -> source.axis() == Axis.CRIT);
    assertThat(sources)
        .filteredOn(source -> source.axis() == Axis.ATTACK_SPEED)
        .extracting(source -> source.value().stripTrailingZeros().toPlainString())
        .containsExactly("5", "4");
    assertThat(sources)
        .filteredOn(source -> source.axis() == Axis.MOVE_SPEED)
        .extracting(source -> source.value().stripTrailingZeros().toPlainString())
        .containsExactly("5");
  }

  @Test
  void 팔찌와_가정_시너지_펫을_분리해_더하고_원문이_없으면_계수로_환산한다() {
    var bracelet =
        new CharacterLoadout.Equipment(
            "팔찌",
            "팔찌",
            null,
            "고대",
            new CharacterLoadout.EquipmentDetail(
                null,
                null,
                null,
                4,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    new CharacterLoadout.BraceletEffect("치명 +120", null),
                    new CharacterLoadout.BraceletEffect("치명타 적중률이 3.4% 증가한다.", null),
                    new CharacterLoadout.BraceletEffect(
                        "공격이 치명타로 적중 시 적에게 주는 피해가 1.5% 증가한다.", null))));
    var profile =
        new CharacterProfile(
            "모험가",
            "루페온",
            "기상술사",
            70,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(
                new CharacterProfile.Stat("치명", "746"), new CharacterProfile.Stat("신속", "1,794")));
    var loadout = new CharacterLoadout(List.of(bracelet), null, null, null, null, null);
    var result =
        CritSpeedCalculation.calculate(
            profile,
            loadout,
            new Assumptions(false, true, 2, new BigDecimal("2.5"), null, null, null, null));

    assertThat(result.sources())
        .filteredOn(source -> source.category() == Category.BRACELET)
        .extracting(source -> source.value().stripTrailingZeros().toPlainString())
        .containsExactly("3.4");
    // 펫 160/27.944 = 5.73, 시너지 20, 서포터 2.5
    assertThat(result.crit().assumedRate()).isEqualByComparingTo("28.23");
    // 치명 746/27.944 = 26.70(Tooltip 없음 → 환산) + 팔찌 3.4
    assertThat(result.crit().observedRate()).isEqualByComparingTo("30.10");
    assertThat(result.speed().swiftnessStat()).isEqualTo(1794);
    assertThat(result.speed().attack().observed()).isEqualByComparingTo("30.81");
    assertThat(result.limitations()).anyMatch(text -> text.contains("치명 Tooltip이 없어"));
  }

  private static CharacterProfile profile(String crit, String swiftness) {
    return new CharacterProfile(
        "언새늘",
        "카단",
        "블레이드",
        70,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(
            new CharacterProfile.Stat(
                "치명",
                crit,
                List.of(
                    "치명타 적중률이 " + critPercent(crit) + "% 증가합니다.",
                    "물약 및 원정대 레벨 보상 효과로 32만큼 영구적으로 증가되었습니다.")),
            new CharacterProfile.Stat(
                "신속",
                swiftness,
                List.of(
                    "공격 속도가 " + speedPercent(swiftness) + "% 증가합니다.",
                    "이동 속도가 " + speedPercent(swiftness) + "% 증가합니다."))));
  }

  private static String critPercent(String stat) {
    return switch (stat) {
      case "576" -> "20.61";
      case "1800" -> "64.42";
      default -> throw new IllegalArgumentException(stat);
    };
  }

  private static String speedPercent(String stat) {
    return switch (stat) {
      case "77" -> "1.32";
      case "1794" -> "30.81";
      default -> throw new IllegalArgumentException(stat);
    };
  }

  private static CharacterLoadout loadout(List<CharacterLoadout.PassiveEffect> nodes) {
    var ring =
        new CharacterLoadout.EquipmentDetail(
            null,
            null,
            null,
            4,
            null,
            List.of(),
            List.of(),
            List.of(new CharacterLoadout.PolishEffect("치명타 적중률", "+1.55%", null)),
            List.of());
    return new CharacterLoadout(
        List.of(
            new CharacterLoadout.Equipment("반지", "반지1", null, "고대", ring),
            new CharacterLoadout.Equipment("반지", "반지2", null, "고대", ring)),
        new CharacterLoadout.Engravings(
            List.of(),
            List.of(),
            List.of(new CharacterLoadout.PassiveEngraving(3, "유물", 3, "아드레날린", ADRENALINE))),
        null,
        null,
        new CharacterLoadout.ArkPassive("아크패시브", true, List.of(), nodes),
        null);
  }

  private static CharacterLoadout.PassiveEffect node(String description, String effect) {
    return new CharacterLoadout.PassiveEffect("진화", description, null, effect);
  }
}
