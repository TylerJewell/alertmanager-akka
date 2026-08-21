package io.akka.alertmanager.domain;

import java.time.Instant;
import java.util.Map;

/**
 * A time-bounded mute rule — SPEC-001 §2, §3 rules 1, 2, 4. State is derived from
 * {@code now}, never stored: both {@code startsAt} and {@code endsAt} are inclusive of
 * {@link SilenceState#ACTIVE} (question-log #1).
 */
public record Silence(String id, Matchers matchers, Instant startsAt, Instant endsAt) {

  public SilenceState stateAt(Instant now) {
    if (now.isBefore(startsAt)) return SilenceState.PENDING;
    if (now.isAfter(endsAt)) return SilenceState.EXPIRED;
    return SilenceState.ACTIVE;
  }

  public boolean mutes(Map<String, String> labels, Instant now) {
    return stateAt(now) == SilenceState.ACTIVE && matchers.matches(labels);
  }
}
