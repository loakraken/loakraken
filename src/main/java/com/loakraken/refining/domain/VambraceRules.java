package com.loakraken.refining.domain;

import java.util.List;

/**
 * 4티어 팔 보호구(완갑) 재련 단계표. 단계마다 기본 성공률(실패마다 기본의 10%씩, 10회까지 오름 — 일반 재련과 같은 규칙), 재료·파편·골드, 숨결 두 가지
 * (용암·빙하: 같은 증가량·최대 개수)를 둔다. 책은 없다. 실링은 넣지 않는다.
 */
public record VambraceRules(String ruleVersion, Source source, List<Stage> stages) {
  /** 읽을 때 표 모양을 확인한다(코드 리뷰 10-25): 목표 1~25단계가 한 번씩, 기본 성공률 > 0, 숨결 두 가지가 같은 증가량. 아니면 시작하지 않는다. */
  public VambraceRules {
    stages = List.copyOf(stages);
    var targets = stages.stream().map(Stage::targetStage).sorted().toList();
    if (!targets.equals(java.util.stream.IntStream.rangeClosed(1, 25).boxed().toList()))
      throw new IllegalArgumentException("팔 보호구 단계표는 목표 1~25단계가 한 번씩 있어야 합니다: " + targets);
    for (var stage : stages) {
      if (!(stage.baseRateBp() > 0))
        throw new IllegalArgumentException(stage.targetStage() + "단계 기본 성공률이 없습니다.");
      if (stage.breaths().size() != 2
          || stage.breaths().get(0).rateBp() != stage.breaths().get(1).rateBp()
          || stage.breaths().stream().anyMatch(breath -> breath.maxCount() <= 0))
        throw new IllegalArgumentException(stage.targetStage() + "단계 숨결은 같은 증가량 두 가지여야 합니다.");
    }
  }

  public record Source(String name, String note, String checkedAt) {}

  /** baseRateBp·숨결 rateBp는 만분율(1 = 0.01%)이고 소수가 있을 수 있다(예: 숨결 +0.375% = 37.5). */
  public record Stage(
      int targetStage,
      double baseRateBp,
      List<RefiningRules.Material> materials,
      long shard,
      long gold,
      List<Breath> breaths) {
    public Stage {
      materials = List.copyOf(materials);
      breaths = List.copyOf(breaths);
    }
  }

  public record Breath(String name, int maxCount, double rateBp) {}

  /** 목표 단계(1~25)의 단계. 없으면 IllegalArgumentException. */
  public Stage stage(int targetStage) {
    return stages.stream()
        .filter(stage -> stage.targetStage() == targetStage)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("팔 보호구 " + targetStage + "단계 규칙이 없습니다."));
  }
}
