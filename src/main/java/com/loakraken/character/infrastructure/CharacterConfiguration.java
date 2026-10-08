package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterCollectionPolicy;
import com.loakraken.character.application.CharacterHistoryPolicy;
import com.loakraken.character.application.CharacterSnapshotPolicy;
import com.loakraken.character.application.CharacterViewPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  CharacterSnapshotPolicy.class,
  CharacterHistoryPolicy.class,
  CharacterViewPolicy.class,
  CharacterCollectionPolicy.class
})
class CharacterConfiguration {}
