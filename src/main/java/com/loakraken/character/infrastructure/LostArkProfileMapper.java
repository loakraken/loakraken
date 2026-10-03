package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.domain.CharacterProfile;
import java.math.BigDecimal;
import java.util.List;

final class LostArkProfileMapper {
  private LostArkProfileMapper() {}

  static CharacterProfile from(LostArkProfileResponse response) {
    if (response == null) throw new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND);
    if (blank(response.characterName())
        || blank(response.serverName())
        || blank(response.className())
        || blank(response.itemLevel()))
      throw new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR);
    return new CharacterProfile(
        response.characterName(),
        response.serverName(),
        response.className(),
        response.characterLevel(),
        number(response.itemLevel()),
        number(response.combatPower()),
        response.expeditionLevel(),
        response.imageUrl(),
        response.title(),
        response.guildName(),
        response.stats() == null
            ? List.of()
            : response.stats().stream()
                .filter(stat -> stat != null)
                .map(stat -> new CharacterProfile.Stat(stat.type(), stat.value()))
                .toList());
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  static BigDecimal number(String value) {
    if (blank(value)) return null;
    try {
      var number = new BigDecimal(value.replace(",", "").strip());
      if (number.signum() < 0) throw new NumberFormatException();
      return number;
    } catch (NumberFormatException exception) {
      throw new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR);
    }
  }
}
