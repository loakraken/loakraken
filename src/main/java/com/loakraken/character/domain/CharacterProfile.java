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
    List<Stat> stats) {
  public CharacterProfile {
    stats = stats == null ? List.of() : List.copyOf(stats);
  }

  public record Stat(String type, String value) {}
}
