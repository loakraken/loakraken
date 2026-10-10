package com.loakraken.character.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("character.history")
public record CharacterHistoryPolicy(boolean enabled) {}
