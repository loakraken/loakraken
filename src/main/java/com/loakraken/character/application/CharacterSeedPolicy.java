package com.loakraken.character.application;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 통계 수집기 시드 목록(#255). 켜져 있으면 수집기가 시드 이름마다 원정대를 한 번 훑어 표본을 넓힌다. names(쉼표 구분 환경 변수)와 file(한 줄에 한 이름,
 * # 주석)을 합친다. <b>시드 파일은 저장소에 커밋하지 않는다</b>(운영 서버 경로만).
 */
@ConfigurationProperties("stats.seed")
public record CharacterSeedPolicy(
    @DefaultValue("false") boolean enabled,
    @DefaultValue List<String> names,
    @DefaultValue("") String file) {
  public CharacterSeedPolicy {
    names = names == null ? List.of() : List.copyOf(names);
    file = file == null ? "" : file.strip();
  }
}
