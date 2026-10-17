package com.loakraken.character.infrastructure;

import com.loakraken.character.application.RosterCombatPowerPolicy;
import com.loakraken.character.application.RosterTransientPort;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * roster-cp:v1:transient:{HMAC} → "전투력|관측 epoch 밀리초"(5분), roster-cp:v1:fetched:{HMAC} → "1"(24시간).
 * HMAC 키는 서버 비밀값이라 키로 이름을 거꾸로 찾을 수 없다. 키·값을 로그에 남기지 않는다. 저장소 장애는 DataAccessException 그대로 던지고 처리는
 * 서비스가 정한다(불러오기는 503, 조회는 없는 값).
 */
@Component
class ValkeyRosterTransient implements RosterTransientPort {
  static final String VALUE_PREFIX = "roster-cp:v1:transient:";
  static final String MARK_PREFIX = "roster-cp:v1:fetched:";
  private final StringRedisTemplate redis;
  private final RosterCombatPowerPolicy policy;

  ValkeyRosterTransient(StringRedisTemplate redis, RosterCombatPowerPolicy policy) {
    this.redis = redis;
    this.policy = policy;
  }

  @Override
  public void put(String serverName, String characterName, Value value, Duration ttl) {
    redis
        .opsForValue()
        .set(
            VALUE_PREFIX + hmac(serverName, characterName),
            (value.combatPower() == null ? "" : value.combatPower().toPlainString())
                + "|"
                + value.observedAt().toEpochMilli(),
            ttl);
  }

  @Override
  public Map<String, Value> find(String serverName, Set<String> characterNames) {
    var names = new ArrayList<>(characterNames);
    var result = new HashMap<String, Value>();
    for (var entry : read(VALUE_PREFIX, serverName, names).entrySet()) {
      var raw = entry.getValue();
      int bar = raw.indexOf('|');
      if (bar < 0) continue;
      try {
        var power = raw.substring(0, bar);
        result.put(
            entry.getKey(),
            new Value(
                power.isEmpty() ? null : new BigDecimal(power),
                Instant.ofEpochMilli(Long.parseLong(raw.substring(bar + 1)))));
      } catch (NumberFormatException ignored) {
        // 형식이 다르면 없는 값으로 둔다.
      }
    }
    return result;
  }

  @Override
  public void mark(String serverName, String characterName, Duration ttl) {
    redis.opsForValue().set(MARK_PREFIX + hmac(serverName, characterName), "1", ttl);
  }

  @Override
  public Set<String> marked(String serverName, Set<String> characterNames) {
    return new HashSet<>(read(MARK_PREFIX, serverName, new ArrayList<>(characterNames)).keySet());
  }

  private Map<String, String> read(String prefix, String serverName, List<String> names) {
    var result = new HashMap<String, String>();
    if (names.isEmpty()) return result;
    var values =
        redis
            .opsForValue()
            .multiGet(names.stream().map(name -> prefix + hmac(serverName, name)).toList());
    if (values == null) return result;
    for (int i = 0; i < names.size(); i++)
      if (values.get(i) != null) result.put(names.get(i), values.get(i));
    return result;
  }

  private String hmac(String serverName, String characterName) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(policy.markerSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of()
          .formatHex(
              mac.doFinal((serverName + "\n" + characterName).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      throw new IllegalStateException("원정대 표시 키를 만들 수 없습니다.");
    }
  }
}
