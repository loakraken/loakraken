package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterCollectionPolicy;
import com.loakraken.character.application.CharacterHistoryPolicy;
import com.loakraken.character.application.CharacterPopularPolicy;
import com.loakraken.character.application.CharacterSeedList;
import com.loakraken.character.application.CharacterSeedPolicy;
import com.loakraken.character.application.CharacterSnapshotPolicy;
import com.loakraken.character.application.CharacterViewPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  CharacterSnapshotPolicy.class,
  CharacterHistoryPolicy.class,
  CharacterViewPolicy.class,
  CharacterCollectionPolicy.class,
  CharacterPopularPolicy.class,
  CharacterSeedPolicy.class
})
class CharacterConfiguration {
  /** 통계 수집기 시드 목록(#255). 꺼져 있으면 빈 목록이다. */
  @Bean
  CharacterSeedList characterSeedList(CharacterSeedPolicy policy) {
    return new CharacterSeedList(policy);
  }
}
