package com.loakraken.refining.application;

import com.loakraken.refining.domain.GrowthCosts;
import com.loakraken.refining.domain.RefiningPlan;
import com.loakraken.refining.domain.RefiningRules;
import org.springframework.stereotype.Service;

@Service
public class RefiningPlanService {
  private final RefiningRules rules;
  private final GrowthCosts growthCosts;

  public RefiningPlanService(RefiningRules rules, GrowthCosts growthCosts) {
    this.rules = rules;
    this.growthCosts = growthCosts;
  }

  public GrowthCosts growthCosts() {
    return growthCosts;
  }

  public RefiningRules rules() {
    return rules;
  }

  public RefiningPlan plan(
      RefiningRules.Grade grade,
      RefiningRules.EquipmentType equipmentType,
      RefiningPlan.Options options) {
    return RefiningPlan.calculate(rules.track(grade, equipmentType), grade, options, growthCosts);
  }
}
