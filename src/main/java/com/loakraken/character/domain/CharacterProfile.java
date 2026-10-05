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
    SkillPoints skillPoints) {
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
        null);
  }

  public record SkillPoints(Integer used, Integer total) {}

  public record Stat(String type, String value) {}
}
