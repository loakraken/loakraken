package com.loakraken;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * 운영 일회성 마이그레이션(F3b): {@code java … migrate}. 스키마 소유 역할로 Flyway만 돌리고 끝낸다 — api 실행 컨테이너는 소유 역할 비밀번호를
 * 갖지 않는다. 설정·Flyway 동작은 api와 같은 Spring Boot 설정(같은 설정 파일·프로필·환경 변수, Flyway 자동 설정)으로 하되, 데이터베이스 연결과
 * Flyway만 띄운다(웹 서버·관리 포트·스케줄러·Valkey 없음). 로그는 api와 같이 표준 출력으로만 나간다.
 */
final class MigrateCommand {
  static final String NAME = "migrate";
  private static final Logger log = LoggerFactory.getLogger(MigrateCommand.class);

  private MigrateCommand() {}

  /** 데이터베이스 연결과 Flyway 자동 설정만. */
  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
  static class MigrateOnly {}

  static boolean requested(String[] args) {
    return args.length > 0 && NAME.equals(args[0]);
  }

  /** 마이그레이션을 끝까지 돌린 컨텍스트(컨텍스트가 뜨는 동안 Flyway가 migrate한다). 닫는 것은 부른 쪽. */
  static ConfigurableApplicationContext start(String... args) {
    return new SpringApplicationBuilder(MigrateOnly.class)
        .web(WebApplicationType.NONE)
        .bannerMode(Banner.Mode.OFF)
        .run(args);
  }

  /** 성공 0, 실패 1. 비밀값은 남기지 않는다(접속 사용자·버전·적용 수만). */
  static int run(String... args) {
    try (var context = start(args)) {
      var flyway = context.getBeanProvider(Flyway.class).getIfAvailable();
      if (flyway == null) {
        log.error("마이그레이션 실패: Flyway가 꺼져 있습니다(spring.flyway.enabled).");
        return 1;
      }
      var info = flyway.info();
      var current = info.current();
      log.info(
          "마이그레이션 완료: 현재 버전 {}, 대기 {}개",
          current == null ? "없음" : current.getVersion(),
          info.pending().length);
      return 0;
    } catch (RuntimeException exception) {
      log.error("마이그레이션 실패: {}", exception.getMessage());
      return 1;
    }
  }
}
