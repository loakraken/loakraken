package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterSnapshotPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CharacterSnapshotPolicy.class)
class CharacterConfiguration {}
