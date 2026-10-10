package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CharacterSnapshotSummary(
    UUID snapshotId,
    Instant fetchedAt,
    String className,
    BigDecimal itemLevel,
    BigDecimal combatPower) {}
