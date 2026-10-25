package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.loakraken.character.presentation.CritSpeedRequest;
import com.loakraken.refining.presentation.RefiningPlanRequest;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.Version;
import tools.jackson.databind.json.JsonMapper;

/**
 * 요청 본문의 숫자 칸에 아주 긴 숫자 문자열이 와도 해석이 짧은 시간 안에 끝난다(거부되거나 바로 읽힘). api 요청 해석과 같은 Spring Boot Jackson
 * 설정으로, 본문 한도(256 KB)에 가까운 길이로 본다. Jackson은 보안 패치 판 이상이어야 한다.
 */
class JsonNumberStringBoundTest {
  private static final Duration BOUND = Duration.ofSeconds(2);
  private static final String LONG_DIGITS = "1".repeat(256 * 1024 - 64);

  private static final ApplicationContextRunner RUNNER =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

  private static void boundedRead(JsonMapper mapper, String json, Class<?> type) {
    assertTimeoutPreemptively(
        BOUND,
        () -> {
          try {
            mapper.readValue(json, type);
          } catch (RuntimeException rejected) {
            // 거부도 정상(짧은 시간 안에 끝나기만 하면 됨).
          }
        });
  }

  @Test
  void 숫자_칸의_긴_숫자_문자열은_짧은_시간_안에_끝난다() {
    RUNNER.run(
        context -> {
          var mapper = context.getBean(JsonMapper.class);
          boundedRead(
              mapper,
              "{\"assumptions\":{\"supportCritRate\":\"" + LONG_DIGITS + "\"}}",
              CritSpeedRequest.class);
          boundedRead(
              mapper,
              "{\"assumptions\":{\"supportCritRate\":\"1." + LONG_DIGITS + "\"}}",
              CritSpeedRequest.class);
          boundedRead(
              mapper,
              "{\"currentArtisanEnergy\":\"" + LONG_DIGITS + "\"}",
              RefiningPlanRequest.class);
          // 숫자처럼 보이다가 끝에서 숫자가 아닌 값(숫자 모양 검사가 가장 오래 걸리는 꼴).
          boundedRead(
              mapper,
              "{\"currentArtisanEnergy\":\"" + LONG_DIGITS + "x\"}",
              RefiningPlanRequest.class);
          boundedRead(
              mapper,
              "{\"assumptions\":{\"supportCritRate\":\"" + LONG_DIGITS + ".x\"}}",
              CritSpeedRequest.class);
          // 보통 값은 그대로 읽힌다.
          assertThat(
                  mapper
                      .readValue(
                          "{\"assumptions\":{\"supportCritRate\":12.5}}", CritSpeedRequest.class)
                      .assumptions()
                      .supportCritRate())
              .isEqualByComparingTo(new BigDecimal("12.5"));
        });
  }

  @Test
  void Jackson은_보안_패치_판_이상이다() {
    Version version = tools.jackson.databind.cfg.PackageVersion.VERSION;
    long number =
        version.getMajorVersion() * 1_000_000L
            + version.getMinorVersion() * 1_000L
            + version.getPatchLevel();
    assertThat(number).as("jackson-databind %s", version).isGreaterThanOrEqualTo(3_001_007L);
  }
}
