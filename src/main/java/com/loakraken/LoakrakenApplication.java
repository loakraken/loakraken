package com.loakraken;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LoakrakenApplication {

  public static void main(String[] args) {
    // 운영 일회성 마이그레이션 서비스(F3b)는 첫 인자 'migrate'로 같은 이미지를 Flyway만 돌리고 끝낸다.
    if (MigrateCommand.requested(args)) {
      System.exit(MigrateCommand.run(java.util.Arrays.copyOfRange(args, 1, args.length)));
    }
    SpringApplication.run(LoakrakenApplication.class, args);
  }
}
