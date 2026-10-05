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
        LostArkText.plain(response.title()),
        LostArkText.plain(response.guildName()),
        response.stats() == null
            ? List.of()
            : response.stats().stream()
                .filter(stat -> stat != null)
                .map(stat -> new CharacterProfile.Stat(stat.type(), stat.value()))
                .toList(),
        skillPoints(response.usingSkillPoint(), response.totalSkillPoint()),
        new CharacterProfile.Inner(
            response.tendencies() == null
                ? List.of()
                : response.tendencies().stream()
                    .filter(tendency -> tendency != null)
                    .map(
                        tendency ->
                            new CharacterProfile.Tendency(
                                LostArkText.plain(tendency.type()),
                                tendency.point(),
                                tendency.maxPoint()))
                    .toList(),
            response.decorations() == null
                ? null
                : new CharacterProfile.Decorations(
                    response.decorations().symbol(),
                    response.decorations().emblems() == null
                        ? List.of()
                        : response.decorations().emblems().stream()
                            .filter(emblem -> emblem != null)
                            .toList()),
            response.honorPoint(),
            LostArkText.plain(response.townName()),
            response.townLevel(),
            LostArkText.plain(response.guildMemberGrade())));
  }

  private static CharacterProfile.SkillPoints skillPoints(Integer used, Integer total) {
    Integer validUsed = used == null || used < 0 ? null : used;
    Integer validTotal = total == null || total < 0 ? null : total;
    return validUsed == null && validTotal == null
        ? null
        : new CharacterProfile.SkillPoints(validUsed, validTotal);
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
