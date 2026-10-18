package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterSibling;
import com.loakraken.common.api.BusinessException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * 원정대 카드 기록 채우기(F-6 후속, PM #96 P2-2·프론트 A안).
 *
 * <p>같은 서버 원정대 중 최근 7일 안에 프로필을 관측하지 못한 캐릭터를 아이템 레벨이 높은 순으로 최대 12명 조회한다. 조회는 캐릭터 검색(armory)과 같은 경로라
 * 사용자 몫 게이트·캐시·카드 기록을 그대로 탄다. 같은 원정대는 10분에 1회만 받는다. 공식 호출은 1회 최대 12회다(원정대 목록은 캐시).
 */
@Service
public class CharacterCardRefreshService {
  public static final int MAX_TARGETS = 12;
  public static final Duration COOLDOWN = Duration.ofMinutes(10);
  public static final Duration FRESH_FOR = Duration.ofDays(7);
  private static final Set<CharacterErrorCode> STOP_ON =
      Set.of(
          CharacterErrorCode.LOST_ARK_RATE_LIMITED,
          CharacterErrorCode.LOST_ARK_API_TIMEOUT,
          CharacterErrorCode.LOST_ARK_API_ERROR,
          CharacterErrorCode.LOST_ARK_NOT_CONFIGURED,
          CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE);
  private final CharacterQueryService queries;
  private final CharacterCardPort cards;
  private final CardRefreshCooldownPort cooldown;
  private final CharacterHistoryPolicy policy;
  private final Clock clock;
  private final com.loakraken.common.lostark.LostArkRequestGate gate;

  public CharacterCardRefreshService(
      CharacterQueryService queries,
      CharacterCardPort cards,
      CardRefreshCooldownPort cooldown,
      CharacterHistoryPolicy policy,
      Clock clock,
      com.loakraken.common.lostark.LostArkRequestGate gate) {
    this.gate = gate;
    this.queries = queries;
    this.cards = cards;
    this.cooldown = cooldown;
    this.policy = policy;
    this.clock = clock;
  }

  public Result refresh(String characterName) {
    if (!policy.enabled())
      throw new CharacterException(CharacterErrorCode.CARD_COLLECTION_DISABLED);
    var siblings = queries.siblings(new SearchCharacterCommand(characterName)).siblings();
    var self =
        siblings.stream()
            .filter(sibling -> characterName.equalsIgnoreCase(sibling.characterName()))
            .findFirst()
            .orElseThrow(() -> new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND));
    String server = self.serverName();
    var roster = new LinkedHashMap<String, CharacterSibling>();
    for (var sibling : siblings)
      if (server.equals(sibling.serverName()) && sibling.characterName() != null)
        roster.put(sibling.characterName(), sibling);

    String key = rosterKey(server, roster.keySet());
    var wait = cooldown.acquire(key, COOLDOWN);
    if (wait.isPresent())
      throw new CharacterException(CharacterErrorCode.CARD_COLLECTION_REFRESH_COOLDOWN, wait.get());
    var now = clock.instant();
    var nextAvailableAt = now.plus(COOLDOWN);

    java.util.Map<String, Instant> refreshed;
    try {
      refreshed = cards.refreshedAt(server, roster.keySet());
    } catch (DataAccessException | TransactionException exception) {
      throw new CharacterException(CharacterErrorCode.CARD_COLLECTION_UNAVAILABLE);
    }
    var freshAfter = now.minus(FRESH_FOR);
    List<CharacterSibling> targets =
        roster.values().stream()
            .filter(
                sibling -> {
                  var at = refreshed.get(sibling.characterName());
                  return at == null || at.isBefore(freshAfter);
                })
            .sorted(
                Comparator.comparing(
                        (CharacterSibling sibling) ->
                            sibling.itemLevel() == null ? BigDecimal.ZERO : sibling.itemLevel())
                    .reversed()
                    .thenComparing(CharacterSibling::characterName))
            .toList();
    int skipped = roster.size() - targets.size();
    int done = 0;
    int failed = 0;
    int attempted = 0;
    boolean stopped = false;
    for (var target : targets) {
      if (attempted == MAX_TARGETS) break;
      // 방문자 한 명의 분당 공식 칸을 다 쓰지 않게, 남은 칸이 적으면 멈추고 나머지는 다음 기회로(#614).
      if (!gate.bulkMayContinue()) {
        stopped = true;
        break;
      }
      attempted++;
      try {
        queries.search(new SearchCharacterCommand(target.characterName(), false));
        done++;
      } catch (BusinessException exception) {
        failed++;
        // 호출 한도·장애(공유 한도·클라이언트 한도·게이트 장애 포함)면 나머지는 다음 기회로 넘긴다.
        if (stops(exception)) {
          stopped = true;
          break;
        }
      }
    }
    // 한 명도 못 채우고 한도·장애로 멈췄으면 10분을 기다리게 하지 않는다(PM-백엔드 #138 P2).
    if (stopped && done == 0) {
      cooldown.release(key);
      nextAvailableAt = now;
    }
    return new Result(
        roster.size(), done, skipped, failed, targets.size() - attempted, nextAvailableAt);
  }

  private static boolean stops(BusinessException exception) {
    var code = exception.errorCode();
    if (code instanceof CharacterErrorCode characterCode) return STOP_ON.contains(characterCode);
    return code instanceof com.loakraken.common.lostark.LostArkErrorCode;
  }

  /** 같은 서버·같은 원정대 이름 집합이면 같은 키. 이름은 정렬해 넣는다. */
  static String rosterKey(String server, java.util.Collection<String> names) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(server.getBytes(StandardCharsets.UTF_8));
      for (var name : names.stream().sorted().toList()) {
        digest.update((byte) '\n');
        digest.update(name.getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(digest.digest(), 0, 16);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  /**
   * @param refreshed 이번에 조회해 기록한 캐릭터 수
   * @param skipped 최근 7일 안에 이미 기록이 있어 건너뛴 수
   * @param failed 못 찾았거나 공식 오류가 난 수
   * @param remaining 1회 상한(12명)이나 공식 오류로 이번에 못 한 수
   */
  public record Result(
      int rosterCharacters,
      int refreshed,
      int skipped,
      int failed,
      int remaining,
      Instant nextAvailableAt) {}
}
