package com.loakraken.character.application;

import java.time.Instant;

public record CharacterTitleObservation(
    String title, Instant firstObservedAt, Instant lastObservedAt) {}
