package com.loakraken;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {
  @Test
  void 도메인_모듈의_의존성_경계를_검증한다() {
    ApplicationModules.of(LoakrakenApplication.class).verify();
  }
}
