package com.loakraken.character.application;

import java.math.BigDecimal;
import java.time.Instant;

public record CharacterObservationPoint(
    Instant observedAt,
    Instant lastConfirmedAt,
    String className,
    Integer characterLevel,
    BigDecimal itemLevel,
    BigDecimal combatPower,
    Integer expeditionLevel) {}
