package com.loakraken.character.domain;

import java.math.BigDecimal;

public record CharacterSibling(
    String characterName,
    String serverName,
    String className,
    Integer characterLevel,
    BigDecimal itemLevel) {}
