package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 배포 env 예시의 고정 망 값(#632): 운영·스테이징이 같은 서버에 올라가도 망 대역이 겹치지 않고, 고정 주소가 자기 망 안에 있는지 본다. 값이 비면 compose가
 * 멈추고(필수 변수), 겹치면 도커가 망을 만들지 못하므로 예시부터 맞춰 둔다.
 */
class DeployNetworkExampleTest {
  private static final List<String> ENVS = List.of("production", "staging");

  @Test
  void 환경별_망_대역은_서로_겹치지_않고_고정_주소는_자기_망_안에_있다() throws Exception {
    var subnets = new ArrayList<String>();
    for (var env : ENVS) {
      var values = read(Path.of("deploy", "stack." + env + ".env.example"));
      var internal = values.get("INTERNAL_SUBNET");
      var metrics = values.get("METRICS_SUBNET");
      assertThat(internal).as(env + " INTERNAL_SUBNET").isNotBlank();
      assertThat(metrics).as(env + " METRICS_SUBNET").isNotBlank();
      assertThat(contains(internal, values.get("WEB_INTERNAL_IP")))
          .as(env + " WEB_INTERNAL_IP")
          .isTrue();
      assertThat(contains(metrics, values.get("API_METRICS_IP")))
          .as(env + " API_METRICS_IP")
          .isTrue();
      subnets.add(env + " internal " + internal);
      subnets.add(env + " metrics " + metrics);
    }
    for (int i = 0; i < subnets.size(); i++)
      for (int j = i + 1; j < subnets.size(); j++)
        assertThat(overlap(cidr(subnets.get(i)), cidr(subnets.get(j))))
            .as(subnets.get(i) + " vs " + subnets.get(j))
            .isFalse();
  }

  private static Map<String, String> read(Path file) throws Exception {
    var values = new HashMap<String, String>();
    for (var line : Files.readAllLines(file)) {
      var trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) continue;
      int eq = trimmed.indexOf('=');
      values.put(trimmed.substring(0, eq).strip(), trimmed.substring(eq + 1).strip());
    }
    return values;
  }

  private static String cidr(String labeled) {
    return labeled.substring(labeled.lastIndexOf(' ') + 1);
  }

  private static long[] range(String cidr) {
    var parts = cidr.split("/");
    int bits = Integer.parseInt(parts[1]);
    long base = ipv4(parts[0]);
    long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
    long start = base & mask;
    return new long[] {start, start | (~mask & 0xFFFFFFFFL)};
  }

  private static boolean overlap(String a, String b) {
    var x = range(a);
    var y = range(b);
    return x[0] <= y[1] && y[0] <= x[1];
  }

  private static boolean contains(String cidr, String ip) {
    if (ip == null || ip.isBlank()) return false;
    var r = range(cidr);
    long value = ipv4(ip);
    return value >= r[0] && value <= r[1];
  }

  private static long ipv4(String ip) {
    var octets = ip.strip().split("\\.");
    long value = 0;
    for (var octet : octets) value = (value << 8) | Integer.parseInt(octet);
    return value;
  }
}
