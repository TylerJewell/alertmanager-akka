package io.akka.alertmanager.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * A submitted alert — SPEC-001 §2. An alert with no {@code endsAt}, or one still in the
 * future, is firing; one whose {@code endsAt} has passed is resolved. The After comparison
 * mirrors the same inclusive/exclusive shape run for silences (question-log #1): resolved
 * only once {@code now} is strictly after {@code endsAt}.
 */
public record Alert(Map<String, String> labels, Instant startsAt, Optional<Instant> endsAt) {

  public boolean isFiring(Instant now) {
    return endsAt.map(e -> !now.isAfter(e)).orElse(true);
  }

  public boolean isResolved(Instant now) {
    return !isFiring(now);
  }
}
