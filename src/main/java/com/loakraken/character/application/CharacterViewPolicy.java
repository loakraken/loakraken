package com.loakraken.character.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("character.views")
public record CharacterViewPolicy(@DefaultValue("true") boolean enabled) {}
