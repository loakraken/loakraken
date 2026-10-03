package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterProfile;
import java.time.Instant;

public record CharacterResult(CharacterProfile profile, Instant fetchedAt) {}
