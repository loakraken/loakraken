package com.loakraken.refining.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 특수 재련 1회 소모량(M42, 리소스 refining/t4-special-refine.json). fromStage(→ fromStage + 1)마다 돌파석 개수. 성공
 * 확률은 그 단계의 공식 기본 확률(t4-refining-rules)이라 이 파일에는 없다. 출처 칸은 파일 안에 있다.
 */
public record SpecialRefineRules(String ruleVersion, Source source, List<Track> tracks) {
  public SpecialRefineRules {
    tracks = List.copyOf(tracks);
  }

  /** capturedOn = 1회 소모량을 본 날짜, singleSource = 출처가 한 곳뿐인지. */
  public record Source(String rule, String perTry, String capturedOn, boolean singleSource) {}

  public record Track(
      RefiningRules.Grade grade,
      RefiningRules.EquipmentType equipmentType,
      Map<Integer, Integer> perTryByFromStage) {
    public Track {
      perTryByFromStage = Map.copyOf(perTryByFromStage);
    }
  }

  /** 그 등급·장비·fromStage의 1회 소모량. 출처가 없으면 빈 값. */
  public Optional<Integer> perTry(
      RefiningRules.Grade grade, RefiningRules.EquipmentType type, int fromStage) {
    return tracks.stream()
        .filter(t -> t.grade() == grade && t.equipmentType() == type)
        .findFirst()
        .map(t -> t.perTryByFromStage().get(fromStage));
  }
}
