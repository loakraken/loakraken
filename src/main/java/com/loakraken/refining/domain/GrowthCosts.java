package com.loakraken.refining.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 장비 성장(경험치) 비용(#200). 재련 전에 단계마다 한 번 장비 경험치를 채우는 운명의 파편·실링이다. 공식 단계표가 없어 계산 검증이 비교 기준 2곳의 단계별 실측과
 * 구간 합으로 맞춘 값을 고정했다(2026-10-10). confirmed=false인 단계는 계산에서 빼고 미확정으로 알린다(2026-10-10 기준 없음: 전율 목표
 * 21단계는 공지 13339로 일반 성장 0 확정, 한계 해방 비용은 별도). 실링은 비교 기준 한 곳 값만 있어 수량 표시용이다.
 */
public record GrowthCosts(
    String ruleVersion, Source source, Research research, List<Track> tracks) {
  public GrowthCosts {
    Objects.requireNonNull(ruleVersion);
    Objects.requireNonNull(source);
    Objects.requireNonNull(research);
    tracks = List.copyOf(tracks);
  }

  public Optional<Stage> stage(
      RefiningRules.Grade grade, RefiningRules.EquipmentType equipmentType, int targetStage) {
    return tracks.stream()
        .filter(track -> track.grade() == grade && track.equipmentType() == equipmentType)
        .flatMap(track -> track.stages().stream())
        .filter(stage -> stage.targetStage() == targetStage)
        .findFirst();
  }

  /** 영지 연구가 이 단계 성장 파편에 걸리는지(업화 목표 11~14단계). */
  public boolean researchApplies(RefiningRules.Grade grade, int targetStage) {
    return research.grade() == grade
        && targetStage >= research.fromTargetStage()
        && targetStage <= research.toTargetStage();
  }

  public record Source(String name, String crossCheckedWith, String verifiedOn) {}

  /** 원정대 영지 연구: 재련 필요 경험치 감소. rateBp는 남는 비율(8000 = 80%). text는 공식 재련 가이드 문구. */
  public record Research(
      RefiningRules.Grade grade, int fromTargetStage, int toTargetStage, int rateBp, String text) {}

  public record Track(
      RefiningRules.Grade grade, RefiningRules.EquipmentType equipmentType, List<Stage> stages) {
    public Track {
      stages = List.copyOf(stages);
    }
  }

  public record Stage(int targetStage, long shard, long silver, boolean confirmed) {}
}
