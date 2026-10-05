package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.util.List;

public record CharacterProfile(
    String characterName,
    String serverName,
    String className,
    Integer characterLevel,
    BigDecimal itemLevel,
    BigDecimal combatPower,
    Integer expeditionLevel,
    String imageUrl,
    String title,
    String guildName,
    List<Stat> stats,
    SkillPoints skillPoints,
    Inner inner) {
  public CharacterProfile {
    stats = stats == null ? List.of() : List.copyOf(stats);
  }

  public CharacterProfile(
      String characterName,
      String serverName,
      String className,
      Integer characterLevel,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      Integer expeditionLevel,
      String imageUrl,
      String title,
      String guildName,
      List<Stat> stats,
      SkillPoints skillPoints) {
    this(
        characterName,
        serverName,
        className,
        characterLevel,
        itemLevel,
        combatPower,
        expeditionLevel,
        imageUrl,
        title,
        guildName,
        stats,
        skillPoints,
        null);
  }

  public CharacterProfile(
      String characterName,
      String serverName,
      String className,
      Integer characterLevel,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      Integer expeditionLevel,
      String imageUrl,
      String title,
      String guildName,
      List<Stat> stats) {
    this(
        characterName,
        serverName,
        className,
        characterLevel,
        itemLevel,
        combatPower,
        expeditionLevel,
        imageUrl,
        title,
        guildName,
        stats,
        null,
        null);
  }

  /** 내실: 성향, 장식(심볼·문장), 명예, 영지, 길드 직위. */
  public record Inner(
      List<Tendency> tendencies,
      Decorations decorations,
      Integer honorPoint,
      String townName,
      Integer townLevel,
      String guildMemberGrade) {
    public Inner {
      tendencies = tendencies == null ? List.of() : List.copyOf(tendencies);
    }
  }

  public record Tendency(String type, Integer point, Integer maxPoint) {}

  public record Decorations(String symbol, List<String> emblems) {
    public Decorations {
      emblems = emblems == null ? List.of() : List.copyOf(emblems);
    }
  }

  public record SkillPoints(Integer used, Integer total) {}

  /** tooltip: 공식 스탯 Tooltip의 정제된 문장(예: "치명타 적중률이 20.61% 증가합니다."). */
  public record Stat(String type, String value, List<String> tooltip) {
    public Stat {
      tooltip = tooltip == null ? List.of() : List.copyOf(tooltip);
    }

    public Stat(String type, String value) {
      this(type, value, List.of());
    }
  }
}
