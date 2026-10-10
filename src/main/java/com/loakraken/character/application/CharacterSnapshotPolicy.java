package com.loakraken.character.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("character.snapshots")
public record CharacterSnapshotPolicy(boolean enabled) {}
