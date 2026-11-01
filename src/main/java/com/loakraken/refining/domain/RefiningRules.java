package com.loakraken.refining.domain;

import java.util.List;
import java.util.Objects;

/** 단계별 재련 규칙. 확률은 만분율(1 = 0.01%)이다. */
public record RefiningRules(String ruleVersion, Source source, List<Track> tracks) {
  public RefiningRules {
    Objects.requireNonNull(ruleVersion);
    Objects.requireNonNull(source);
    tracks = List.copyOf(tracks);
  }

  public Track track(Grade grade, EquipmentType equipmentType) {
    return tracks.stream()
        .filter(track -> track.grade() == grade && track.equipmentType() == equipmentType)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 재련 구간입니다."));
  }

  public enum Grade {
    UPHWA,
    JEONYUL
  }

  /** ARM_GUARD는 팔 보호구(완갑): 등급별 트랙이 없고 {@link VambraceRules} 한 표로 계산한다(재련 계획만, 이 규칙의 트랙에는 없음). */
  public enum EquipmentType {
    WEAPON,
    ARMOR,
    ARM_GUARD
  }

  public record Source(
      String name, String commit, String license, String crossCheckedWith, String verifiedOn) {}

  public record Track(Grade grade, EquipmentType equipmentType, List<Stage> stages) {
    public Track {
      stages = List.copyOf(stages);
    }

    public Stage stage(int targetStage) {
      return stages.stream()
          .filter(stage -> stage.targetStage() == targetStage)
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 재련 단계입니다."));
    }

    public int minTargetStage() {
      return stages.stream().mapToInt(Stage::targetStage).min().orElseThrow();
    }

    public int maxTargetStage() {
      return stages.stream().mapToInt(Stage::targetStage).max().orElseThrow();
    }
  }

  public record Stage(
      int targetStage,
      int baseRateBp,
      List<Material> materials,
      long shard,
      long gold,
      Breath breath,
      Book book) {
    public Stage {
      materials = List.copyOf(materials);
    }
  }

  public record Material(String name, long count) {}

  public record Breath(String name, int maxCount, int rateBp) {}

  public record Book(String name, int rateBp) {}
}
