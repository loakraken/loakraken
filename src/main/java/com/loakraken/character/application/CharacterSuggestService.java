package com.loakraken.character.application;

import java.text.Normalizer;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * 캐릭터 이름 자동완성(#369). 공식 API를 부르지 않고 우리 DB에 기록된 캐릭터에서 이름 앞부분이 같은 캐릭터를 찾는다. 검색한 사람·입력은 기록하지 않는다. 처리정지
 * 목록은 뺀다.
 */
@Service
public class CharacterSuggestService {
  public static final int LIMIT = 8;
  public static final int MAX_LENGTH = 12;

  private final CharacterSuggestPort suggestions;

  public CharacterSuggestService(CharacterSuggestPort suggestions) {
    this.suggestions = suggestions;
  }

  /** 너무 짧거나 긴 입력은 빈 목록이다(오류 대신 빈 결과, 입력 중인 검색창에 맞춤). */
  public List<CharacterSuggestPort.Suggestion> suggest(String query) {
    var prefix = normalize(query);
    if (prefix == null) return List.of();
    try {
      return suggestions.suggest(prefix, LIMIT);
    } catch (DataAccessException | TransactionException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE);
    }
  }

  /**
   * NFC로 맞추고 앞뒤 공백을 지운다. 한글 완성형 글자만이면 1자부터, 영문·숫자가 섞이면 2자부터 찾는다(1자 영문은 맞는 이름이 너무 많다). 12자를 넘으면 게임
   * 이름 길이를 넘으므로 찾지 않는다.
   */
  static String normalize(String query) {
    if (query == null) return null;
    var value = Normalizer.normalize(query.strip(), Normalizer.Form.NFC);
    if (value.isEmpty() || value.length() > MAX_LENGTH) return null;
    boolean hangulOnly = value.chars().allMatch(c -> c >= 0xAC00 && c <= 0xD7A3);
    return hangulOnly || value.length() >= 2 ? value : null;
  }
}
