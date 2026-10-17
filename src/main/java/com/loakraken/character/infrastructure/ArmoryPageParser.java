package com.loakraken.character.infrastructure;

import com.loakraken.character.application.ArmoryPageFetchException;
import com.loakraken.character.application.ArmoryPagePort;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

/**
 * 공식 전투정보실 캐릭터 페이지 HTML에서 필요한 값만 뽑는다(#531). 생활 스킬 목록은 {@code ul.profile-skill-life__list > li}마다 이름
 * {@code strong}과 {@code Lv.N} {@code span}이다. 목록이 없으면 구조가 바뀐 것으로 보고 실패시킨다(추측값을 내지 않음).
 */
final class ArmoryPageParser {
  private static final Pattern LEVEL = Pattern.compile("Lv\\.?\\s*(\\d{1,4})");

  private ArmoryPageParser() {}

  static ArmoryPagePort.Page parse(String html) {
    var document = Jsoup.parse(html);
    var rows = document.select("ul.profile-skill-life__list > li");
    if (rows.isEmpty())
      throw new ArmoryPageFetchException(ArmoryPageFetchException.Kind.PARSE_ERROR, null);
    var skills = new ArrayList<ArmoryPagePort.LifeSkill>();
    for (var row : rows) {
      var name = row.selectFirst("strong");
      if (name == null || name.text().isBlank()) continue;
      var level = row.selectFirst("span");
      Integer value = null;
      if (level != null) {
        var matcher = LEVEL.matcher(level.text());
        if (matcher.find()) value = Integer.valueOf(matcher.group(1));
      }
      skills.add(new ArmoryPagePort.LifeSkill(name.text().strip(), value));
    }
    if (skills.isEmpty())
      throw new ArmoryPageFetchException(ArmoryPageFetchException.Kind.PARSE_ERROR, null);
    return new ArmoryPagePort.Page(skills);
  }
}
