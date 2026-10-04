package com.loakraken.character.domain;

import java.util.ArrayList;
import java.util.List;

/** 관측값만 설명한다. 데이터 부재를 미착용이나 성능 부족으로 해석하지 않는다. */
public record CharacterDiagnostics(String ruleVersion, List<Finding> findings) {
  public CharacterDiagnostics {
    findings = List.copyOf(findings);
  }

  public static CharacterDiagnostics from(CharacterAnalysis analysis) {
    var findings = new ArrayList<Finding>();
    if (analysis.equipmentCount() == 0) {
      add(
          findings,
          Code.EQUIPMENT_NOT_OBSERVED,
          Category.DATA_GAP,
          "/data/analysis/equipmentCount",
          0,
          "장비 목록에서 확인된 항목이 없습니다. 미착용 여부는 이 결과만으로 판단할 수 없습니다.");
    }
    var gems = analysis.gems();
    if (gems == null) {
      unavailable(findings, Code.GEMS_UNAVAILABLE, "gems", "보석");
    } else if (gems.count() == 0) {
      add(
          findings,
          Code.GEMS_NOT_OBSERVED,
          Category.DATA_GAP,
          "/data/analysis/gems/count",
          0,
          "보석 목록에서 확인된 항목이 없습니다. 장착 상태를 확인해 주세요.");
    } else if (gems.unknownLevelCount() > 0) {
      add(
          findings,
          Code.GEM_LEVEL_UNKNOWN,
          Category.DATA_GAP,
          "/data/analysis/gems/unknownLevelCount",
          gems.unknownLevelCount(),
          "레벨을 확인할 수 없는 보석이 평균과 분포 계산에서 제외되었습니다.");
    }
    var cards = analysis.cards();
    if (cards == null) {
      unavailable(findings, Code.CARDS_UNAVAILABLE, "cards", "카드");
    } else if (cards.count() == 0) {
      add(
          findings,
          Code.CARDS_NOT_OBSERVED,
          Category.DATA_GAP,
          "/data/analysis/cards/count",
          0,
          "카드 목록에서 확인된 항목이 없습니다. 장착 상태를 확인해 주세요.");
    } else if (cards.unknownAwakeningCount() > 0) {
      add(
          findings,
          Code.CARD_AWAKENING_UNKNOWN,
          Category.DATA_GAP,
          "/data/analysis/cards/unknownAwakeningCount",
          cards.unknownAwakeningCount(),
          "각성 수를 확인할 수 없는 카드가 각성 합계에서 제외되었습니다.");
    }
    if (analysis.engravings() == null) {
      unavailable(findings, Code.ENGRAVINGS_UNAVAILABLE, "engravings", "각인");
    }
    var passive = analysis.arkPassive();
    if (passive == null) {
      unavailable(findings, Code.ARK_PASSIVE_UNAVAILABLE, "arkPassive", "아크패시브");
    } else {
      if (passive.enabled() == null) {
        add(
            findings,
            Code.ARK_PASSIVE_STATE_UNKNOWN,
            Category.DATA_GAP,
            "/data/analysis/arkPassive/enabled",
            null,
            "아크패시브 활성 여부를 확인할 수 없습니다.");
      } else if (!passive.enabled()) {
        add(
            findings,
            Code.ARK_PASSIVE_DISABLED,
            Category.SETTING_CHECK,
            "/data/analysis/arkPassive/enabled",
            null,
            "아크패시브가 비활성으로 조회되었습니다. 의도한 세팅인지 확인해 주세요.");
      }
      for (int index = 0; index < passive.points().size(); index++) {
        if (passive.points().get(index).value() == null) {
          add(
              findings,
              Code.ARK_PASSIVE_POINT_UNKNOWN,
              Category.DATA_GAP,
              "/data/analysis/arkPassive/points/" + index + "/value",
              null,
              "아크패시브 포인트 값을 확인할 수 없습니다. 포인트 부족 여부는 판단하지 않습니다.");
        }
      }
    }
    var grid = analysis.arkGrid();
    if (grid == null) {
      unavailable(findings, Code.ARK_GRID_UNAVAILABLE, "arkGrid", "아크그리드");
    } else {
      if (grid.coreCount() == 0) {
        add(
            findings,
            Code.ARK_GRID_NOT_OBSERVED,
            Category.DATA_GAP,
            "/data/analysis/arkGrid/coreCount",
            0,
            "아크그리드 코어 목록에서 확인된 항목이 없습니다.");
      }
      if (grid.inactiveGemCount() > 0) {
        add(
            findings,
            Code.ARK_GRID_GEM_INACTIVE,
            Category.SETTING_CHECK,
            "/data/analysis/arkGrid/inactiveGemCount",
            grid.inactiveGemCount(),
            "비활성으로 조회된 아크그리드 젬이 있습니다. 의도한 세팅인지 확인해 주세요.");
      }
      if (grid.unknownGemCount() > 0) {
        add(
            findings,
            Code.ARK_GRID_GEM_STATE_UNKNOWN,
            Category.DATA_GAP,
            "/data/analysis/arkGrid/unknownGemCount",
            grid.unknownGemCount(),
            "활성 여부를 확인할 수 없는 아크그리드 젬이 있습니다.");
      }
    }
    return new CharacterDiagnostics("observed-v1", findings);
  }

  private static void unavailable(List<Finding> findings, Code code, String section, String label) {
    add(
        findings,
        code,
        Category.DATA_GAP,
        "/data/analysis/" + section,
        null,
        label + " 정보가 제공되지 않아 상태를 판단할 수 없습니다.");
  }

  private static void add(
      List<Finding> findings,
      Code code,
      Category category,
      String evidencePath,
      Integer observedCount,
      String message) {
    findings.add(new Finding(code, category, evidencePath, observedCount, message));
  }

  public enum Category {
    DATA_GAP,
    SETTING_CHECK
  }

  public enum Code {
    EQUIPMENT_NOT_OBSERVED,
    GEMS_UNAVAILABLE,
    GEMS_NOT_OBSERVED,
    GEM_LEVEL_UNKNOWN,
    CARDS_UNAVAILABLE,
    CARDS_NOT_OBSERVED,
    CARD_AWAKENING_UNKNOWN,
    ENGRAVINGS_UNAVAILABLE,
    ARK_PASSIVE_UNAVAILABLE,
    ARK_PASSIVE_STATE_UNKNOWN,
    ARK_PASSIVE_DISABLED,
    ARK_PASSIVE_POINT_UNKNOWN,
    ARK_GRID_UNAVAILABLE,
    ARK_GRID_NOT_OBSERVED,
    ARK_GRID_GEM_INACTIVE,
    ARK_GRID_GEM_STATE_UNKNOWN
  }

  public record Finding(
      Code code, Category category, String evidencePath, Integer observedCount, String message) {}
}
