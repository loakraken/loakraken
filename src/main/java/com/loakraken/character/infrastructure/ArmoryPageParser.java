package com.loakraken.character.infrastructure;

import com.loakraken.character.application.ArmoryPageFetchException;
import com.loakraken.character.application.ArmoryPagePort;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

/**
 * 공식 전투정보실 캐릭터 페이지 HTML에서 필요한 값만 뽑는다(#531·#532).
 *
 * <ul>
 *   <li>생활 스킬: {@code ul.profile-skill-life__list > li}마다 이름 {@code strong}과 {@code Lv.N} {@code
 *       span}. 목록이 없으면 구조가 바뀐 것으로 보고 실패시킨다(추측값을 내지 않음).
 *   <li>식별 값: 페이지 스크립트 변수 {@code _memberNo}·{@code _worldNo}·{@code _pcId}(스킬 코드 요청에 씀). 하나라도 없으면
 *       null — 생활 스킬은 그대로 준다. 값은 메모리에서만 쓴다.
 * </ul>
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
    var scripts = new StringBuilder();
    for (var script : document.select("script:not([src])"))
      scripts.append(script.data()).append('\n');
    var memberNo = variable(scripts, "_memberNo");
    var worldNo = variable(scripts, "_worldNo");
    var pcId = variable(scripts, "_pcId");
    var identity =
        memberNo == null || worldNo == null || pcId == null
            ? null
            : new ArmoryPagePort.Identity(memberNo, worldNo, pcId);
    return new ArmoryPagePort.Page(skills, identity);
  }

  /** {@code var 이름 = '값';} 꼴. 값은 base64 꼴 문자만(따옴표·공백·꺾쇠 없음), 길이 상한 200. */
  private static String variable(CharSequence scripts, String name) {
    var matcher =
        Pattern.compile(
                "var\\s+" + Pattern.quote(name) + "\\s*=\\s*['\"]([A-Za-z0-9+/=_-]{1,200})['\"]")
            .matcher(scripts);
    return matcher.find() ? matcher.group(1) : null;
  }
}
