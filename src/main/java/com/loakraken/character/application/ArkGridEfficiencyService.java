package com.loakraken.character.application;

import com.loakraken.character.application.CombatPowerSimulationService.Status;
import com.loakraken.character.domain.CombatPowerArkGridModel;
import com.loakraken.character.domain.CombatPowerReasons;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 캐릭터 응답의 아크 그리드 효율(L29·L39, 계산 검증 #368). 팔찌 효율과 같은 정의(지금 전투력 ÷ 뺀 전투력 − 1)다.
 *
 * <ul>
 *   <li>percent = Π 코어 배수 × 젬 옵션 배수 − 1
 *   <li>coresPercent = Π 코어 배수 − 1
 *   <li>lines = 코어마다 한 줄(그 코어만 뺐을 때 = C(P)) + 젬 옵션 합계 한 줄
 * </ul>
 *
 * 곱 구조라 줄별 합은 전체 값과 같지 않다(줄별 값은 그 줄만 뺀 값). 한 줄이라도 계산하지 못하면 전체도 계산하지 않는다(팔찌와 같은 규칙). 딜러만 계산하고, 시뮬레이터
 * 플래그와 무관하며 공식 호출이 없다. 아크 그리드가 없으면 null이다.
 */
@Service
public class ArkGridEfficiencyService {
  private static final MathContext MATH = MathContext.DECIMAL64;
  private static final String NO_OFFICIAL_BASIS =
      CombatPowerReasons.NO_OFFICIAL_COMBAT_POWER.text();
  static final String OK_REASON = "아크 그리드 코어와 젬 옵션을 모두 뺀 전투력보다 지금 전투력이 몇 % 높은지입니다.";
  private final CombatPowerArkGridModel.Coefficients coefficients;

  public ArkGridEfficiencyService(CombatPowerArkGridModel.Coefficients coefficients) {
    this.coefficients = coefficients;
  }

  public record ArkGridEfficiency(
      BigDecimal percent,
      BigDecimal coresPercent,
      Status status,
      String reason,
      List<ArkGridLineEfficiency> lines,
      String ruleVersion) {
    public String reasonCode() {
      return status == Status.OK ? null : CombatPowerReasons.classify(reason).code();
    }
  }

  /**
   * @param slotIndex 코어 줄이면 공식 ArkGrid.Slots의 Index, 젬 합계 줄이면 null
   */
  public record ArkGridLineEfficiency(
      Integer slotIndex, String text, BigDecimal percent, Status status, String reason) {
    public String reasonCode() {
      return status == Status.OK ? null : CombatPowerReasons.classify(reason).code();
    }
  }

  public ArkGridEfficiency efficiency(CharacterResult result) {
    var grid = result.details().loadout().arkGrid();
    if (grid == null || grid.slots().isEmpty()) return null;
    String blocked = CombatPowerSimulationService.roleReason(result);
    if (blocked == null && result.details().profile().combatPower() == null)
      blocked = NO_OFFICIAL_BASIS;
    var gems = CombatPowerArkGridModel.gems(coefficients, grid.effects());
    var lines = new ArrayList<ArkGridLineEfficiency>();
    var cores = BigDecimal.ONE;
    String firstReason = null;
    for (var slot : grid.slots()) {
      var text = text(slot);
      var index = slot == null ? null : slot.index();
      if (blocked != null) {
        lines.add(new ArkGridLineEfficiency(index, text, null, Status.UNSUPPORTED, blocked));
        continue;
      }
      var core = CombatPowerArkGridModel.core(coefficients, slot);
      if (core.pct() == null) {
        if (firstReason == null) firstReason = core.reason();
        lines.add(new ArkGridLineEfficiency(index, text, null, Status.UNSUPPORTED, core.reason()));
        continue;
      }
      cores = cores.multiply(CombatPowerArkGridModel.factor(core.pct()), MATH);
      lines.add(
          new ArkGridLineEfficiency(
              index,
              text,
              core.pct().setScale(2, java.math.RoundingMode.HALF_UP),
              Status.OK,
              null));
    }
    if (blocked != null) {
      lines.add(new ArkGridLineEfficiency(null, gems.text(), null, Status.UNSUPPORTED, blocked));
      return unsupported(null, blocked, lines);
    }
    if (gems.ratio() == null) {
      lines.add(
          new ArkGridLineEfficiency(null, gems.text(), null, Status.UNSUPPORTED, gems.reason()));
    } else {
      lines.add(new ArkGridLineEfficiency(null, gems.text(), gems.pct(), Status.OK, null));
    }
    var coresPercent = firstReason == null ? CombatPowerArkGridModel.percent(cores) : null;
    if (firstReason != null) return unsupported(coresPercent, firstReason, lines);
    if (gems.ratio() == null) return unsupported(coresPercent, gems.reason(), lines);
    return new ArkGridEfficiency(
        CombatPowerArkGridModel.percent(cores.multiply(gems.ratio(), MATH)),
        coresPercent,
        Status.OK,
        OK_REASON,
        List.copyOf(lines),
        coefficients.ruleVersion());
  }

  private ArkGridEfficiency unsupported(
      BigDecimal coresPercent, String reason, List<ArkGridLineEfficiency> lines) {
    return new ArkGridEfficiency(
        null,
        coresPercent,
        Status.UNSUPPORTED,
        reason,
        List.copyOf(lines),
        coefficients.ruleVersion());
  }

  private static String text(com.loakraken.character.domain.CharacterLoadout.GridSlot slot) {
    if (slot == null || slot.name() == null) return "코어";
    var text = new StringBuilder(slot.name().strip());
    if (slot.grade() != null) text.append(' ').append(slot.grade());
    if (slot.point() != null) text.append(' ').append(slot.point()).append('P');
    return text.toString();
  }
}
