package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterSibling;
import com.loakraken.common.api.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * 원정대 캐릭터별 전투력(L19, 볼트 「원정대 캐릭터별 전투력 설계 (L19)」).
 *
 * <p>조회는 원정대 목록 캐시(/siblings와 같은 캐시, 없으면 공식 1회)와 우리 DB 기록만 쓴다. 불러오기는 사용자가 누를 때만, 기록이 없거나 오래된(기본
 * 24시간) 캐릭터를 아이템 레벨 높은 순으로 최대 12명 공식 프로필 API(전투력 포함, 2026-10-12 확인)로 부르고 검색과 같은 기록 경로로 저장한다. 같은
 * 원정대는 한 번으로 묶고(single-flight) 끝나면 10분 쿨다운을 둔다. 공식 호출은 사용자 몫 30을 남기는 쪽으로만 하고, 모자라면 받은 만큼만 저장해 부분
 * 결과를 준다. 처리정지 목록 캐릭터는 응답이 기록 없는 캐릭터와 같고 불러와도 기록하지 않는다.
 */
@Service
public class RosterCombatPowerService {
  private static final Set<CharacterErrorCode> STOP_ON =
      Set.of(
          CharacterErrorCode.LOST_ARK_RATE_LIMITED,
          CharacterErrorCode.LOST_ARK_API_TIMEOUT,
          CharacterErrorCode.LOST_ARK_API_ERROR,
          CharacterErrorCode.LOST_ARK_NOT_CONFIGURED);
  private final CharacterQueryService queries;
  private final CharacterDetailsPort details;
  private final RosterCombatPowerPort records;
  private final RosterRefreshCooldownPort cooldown;
  private final CharacterHistoryService history;
  private final CharacterStopList stopList;
  private final CharacterMetrics metrics;
  private final RosterCombatPowerPolicy policy;
  private final Clock clock;
  private final SingleFlight<Refresh> flights = new SingleFlight<>();

  public RosterCombatPowerService(
      CharacterQueryService queries,
      CharacterDetailsPort details,
      RosterCombatPowerPort records,
      RosterRefreshCooldownPort cooldown,
      CharacterHistoryService history,
      CharacterStopList stopList,
      CharacterMetrics metrics,
      RosterCombatPowerPolicy policy,
      Clock clock) {
    this.queries = queries;
    this.details = details;
    this.records = records;
    this.cooldown = cooldown;
    this.history = history;
    this.stopList = stopList;
    this.metrics = metrics;
    this.policy = policy;
    this.clock = clock;
  }

  /** 원정대 한 캐릭터의 공식 원정대 값과 우리 DB 전투력 기록. 기록이 없으면 combatPower·observedAt이 null. */
  public record Item(
      String characterName,
      String serverName,
      String className,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      Instant combatPowerObservedAt) {}

  public record View(List<Item> items, int missingCount, Instant refreshAvailableAt) {}

  public record Refresh(List<Item> items, int fetched, int remaining, Instant refreshAvailableAt) {}

  public View view(String characterName) {
    var roster = roster(characterName);
    var items = items(roster);
    var left = cooldown.remaining(roster.key()).orElse(null);
    // missingCount는 null 줄 수 그대로다. 처리정지를 빼면 그 자체로 처리정지가 드러난다(PM-백엔드 #410 정정).
    return new View(
        items,
        (int) items.stream().filter(item -> item.combatPower() == null).count(),
        left == null ? null : clock.instant().plus(left));
  }

  public Refresh refresh(String characterName) {
    var roster = roster(characterName);
    return flights.run(roster.key(), () -> fetch(roster));
  }

  private Refresh fetch(Roster roster) {
    var wait = cooldown.acquire(roster.key(), policy.cooldown());
    if (wait.isPresent()) {
      metrics.rosterRefresh("cooldown");
      throw new CharacterException(CharacterErrorCode.ROSTER_REFRESH_COOLDOWN, wait.get());
    }
    var now = clock.instant();
    var known = known(roster);
    var stoppedNames = stopped(roster);
    var staleBefore = now.minus(policy.staleAfter());
    var targets =
        roster.members().values().stream()
            // 처리정지 목록은 공식 호출 전에 뺀다(PM-프론트·PM-백엔드 #410 P1). fetched·remaining에도 넣지 않는다.
            .filter(member -> !stoppedNames.contains(member.characterName()))
            .filter(
                member -> {
                  var record = known.get(member.characterName());
                  return record == null
                      || record.observedAt() == null
                      || record.observedAt().isBefore(staleBefore);
                })
            .sorted(
                Comparator.comparing(
                        (CharacterSibling member) ->
                            member.itemLevel() == null ? BigDecimal.ZERO : member.itemLevel())
                    .reversed()
                    .thenComparing(CharacterSibling::characterName))
            .toList();
    int fetched = 0;
    int attempted = 0;
    boolean stopped = false;
    for (var target : targets) {
      if (attempted == policy.maxTargets()) break;
      attempted++;
      try {
        var profile = details.fetchProfile(target.characterName(), policy.reserve());
        // 처리정지 목록이면 받은 값을 기록하지 않는다(B5). 응답은 기록 없는 캐릭터와 같다.
        if (profile != null && stopList.recordable(profile.characterName(), profile.serverName()))
          history.record(profile, clock.instant());
        fetched++;
      } catch (BusinessException exception) {
        // 사용자 몫이 모자라거나 공식 장애면 받은 만큼만 두고 멈춘다. 없는 캐릭터 등은 건너뛴다.
        if (exception.errorCode() instanceof CharacterErrorCode code && !STOP_ON.contains(code))
          continue;
        stopped = true;
        attempted--;
        break;
      }
    }
    int remaining = targets.size() - attempted;
    var availableAt = now.plus(policy.cooldown());
    if (stopped && fetched == 0) {
      // 한 명도 못 불렀으면 쿨다운을 풀어 바로 다시 누를 수 있게 한다.
      cooldown.release(roster.key());
      availableAt = null;
    }
    metrics.rosterRefresh(stopped || remaining > 0 ? (fetched == 0 ? "limited" : "partial") : "ok");
    return new Refresh(items(roster), fetched, remaining, availableAt);
  }

  private List<Item> items(Roster roster) {
    var known = known(roster);
    var items = new ArrayList<Item>();
    for (var member : roster.members().values()) {
      var record = known.get(member.characterName());
      items.add(
          new Item(
              member.characterName(),
              member.serverName(),
              member.className(),
              member.itemLevel(),
              record == null ? null : record.combatPower(),
              record == null ? null : record.observedAt()));
    }
    return List.copyOf(items);
  }

  private Map<String, RosterCombatPowerPort.Observed> known(Roster roster) {
    try {
      return records.find(roster.serverName(), roster.members().keySet());
    } catch (DataAccessException | TransactionException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE);
    }
  }

  private java.util.Set<String> stopped(Roster roster) {
    try {
      return records.stopped(roster.serverName(), roster.members().keySet());
    } catch (DataAccessException | TransactionException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE);
    }
  }

  private record Roster(String serverName, Map<String, CharacterSibling> members, String key) {}

  /** 원정대 목록(캐시, 없으면 공식 1회) 가운데 조회한 캐릭터와 같은 서버. */
  private Roster roster(String characterName) {
    var command = new SearchCharacterCommand(characterName);
    var siblings = queries.siblings(command).siblings();
    var self =
        siblings.stream()
            .filter(sibling -> command.characterName().equalsIgnoreCase(sibling.characterName()))
            .findFirst()
            .orElseThrow(() -> new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND));
    var members = new LinkedHashMap<String, CharacterSibling>();
    for (var sibling : siblings)
      if (self.serverName().equals(sibling.serverName()) && sibling.characterName() != null)
        members.put(sibling.characterName(), sibling);
    return new Roster(
        self.serverName(),
        members,
        CharacterCardRefreshService.rosterKey(self.serverName(), members.keySet()));
  }
}
