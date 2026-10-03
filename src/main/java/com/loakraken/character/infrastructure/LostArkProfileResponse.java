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
    @JsonProperty("Stats") List<Stat> stats) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Stat(@JsonProperty("Type") String type, @JsonProperty("Value") String value) {}
}
