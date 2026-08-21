package io.akka.alertmanager.domain;

import akka.javasdk.annotations.TypeName;
import java.time.Instant;
import java.util.Map;

/**
 * Every event carries the {@code now} used to make its decision, so replay reproduces the
 * same aggregation-group timers and silence clamp the original command computed — a decision
 * procedure over time cannot be replayed correctly against the wall clock at replay time.
 */
public sealed interface AlertingEvent {

  /** {@code endsAt} is {@code null} for a firing alert with no known resolution time. */
  @TypeName("alert-submitted")
  record AlertSubmitted(Map<String, String> labels, Instant startsAt, Instant endsAt, Instant now)
      implements AlertingEvent {}

  @TypeName("silence-created")
  record SilenceCreated(String id, Matchers matchers, Instant startsAt, Instant endsAt, Instant now)
      implements AlertingEvent {}

  @TypeName("silence-deleted")
  record SilenceDeleted(String id) implements AlertingEvent {}

  @TypeName("group-flushed")
  record GroupFlushed(String groupKey, Instant now) implements AlertingEvent {}
}
