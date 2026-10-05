package com.loakraken.refining.application;

import com.loakraken.refining.domain.RefiningPlan;
import com.loakraken.refining.domain.RefiningRules;
import org.springframework.stereotype.Service;

@Service
public class RefiningPlanService {
  private final RefiningRules rules;

  public RefiningPlanService(RefiningRules rules) {
    this.rules = rules;
  }

  public RefiningRules rules() {
    return rules;
  }

  public RefiningPlan plan(
      RefiningRules.Grade grade,
      RefiningRules.EquipmentType equipmentType,
      RefiningPlan.Options options) {
    return RefiningPlan.calculate(rules.track(grade, equipmentType), grade, options);
  }
}
