package com.loakraken.character.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record LostArkProfileResponse(
    @JsonProperty("CharacterName") String characterName,
    @JsonProperty("ServerName") String serverName,
    @JsonProperty("CharacterClassName") String className,
    @JsonProperty("CharacterLevel") Integer characterLevel,
    @JsonProperty("ItemAvgLevel") String itemLevel,
    @JsonProperty("CombatPower") String combatPower,
    @JsonProperty("ExpeditionLevel") Integer expeditionLevel,
    @JsonProperty("CharacterImage") String imageUrl,
    @JsonProperty("Title") String title,
    @JsonProperty("GuildName") String guildName,
    @JsonProperty("Stats") List<Stat> stats,
    @JsonProperty("UsingSkillPoint") Integer usingSkillPoint,
    @JsonProperty("TotalSkillPoint") Integer totalSkillPoint,
    @JsonProperty("Tendencies") List<Tendency> tendencies,
    @JsonProperty("Decorations") Decorations decorations,
    @JsonProperty("HonorPoint") Integer honorPoint,
    @JsonProperty("TownName") String townName,
    @JsonProperty("TownLevel") Integer townLevel,
    @JsonProperty("GuildMemberGrade") String guildMemberGrade) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Tendency(
      @JsonProperty("Type") String type,
      @JsonProperty("Point") Integer point,
      @JsonProperty("MaxPoint") Integer maxPoint) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Decorations(
      @JsonProperty("Symbol") String symbol, @JsonProperty("Emblems") List<String> emblems) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Stat(
      @JsonProperty("Type") String type,
      @JsonProperty("Value") String value,
      @JsonProperty("Tooltip") List<String> tooltip) {}
}
