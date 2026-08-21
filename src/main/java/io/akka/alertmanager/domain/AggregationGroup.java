package io.akka.alertmanager.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Alerts sharing one group-label set, and when they are next due to flush — SPEC-001 §2, §3
 * rules 7, 8, 9. Pure: {@link #onInsert} and {@link #flush} both return a new group.
 */
public record AggregationGroup(
    String groupKey,
    Map<String, String> groupLabels,
    Duration groupWait,
    Duration groupInterval,
    Map<String, Alert> members,
    Instant nextFlushAt) {

  public static AggregationGroup empty(
      String groupKey, Map<String, String> groupLabels, Duration groupWait, Duration groupInterval) {
    return new AggregationGroup(groupKey, groupLabels, groupWait, groupInterval, Map.of(), null);
  }

  /** A stable key from a label set — sorted so the same labels always fingerprint the same way. */
  public static String fingerprint(Map<String, String> labels) {
    var sorted = new TreeMap<>(labels);
    var sb = new StringBuilder();
    sorted.forEach((k, v) -> sb.append(k).append('=').append(v).append(','));
    return sb.toString();
  }

  /**
   * Rule 9: the first alert into an empty group schedules the first flush at {@code now +
   * groupWait} — unless the alert's own {@code startsAt} is already older than {@code
   * groupWait}, in which case the flush is immediate. A later insert into an already-scheduled
   * group leaves the pending flush time untouched.
   */
  public AggregationGroup onInsert(Alert alert, Instant now) {
    var nextMembers = new HashMap<>(members);
    nextMembers.put(fingerprint(alert.labels()), alert);

    Instant flushAt = nextFlushAt;
    if (members.isEmpty()) {
      flushAt = alert.startsAt().plus(groupWait).isBefore(now) ? now : now.plus(groupWait);
    }
    return new AggregationGroup(groupKey, groupLabels, groupWait, groupInterval, Map.copyOf(nextMembers), flushAt);
  }

  public boolean dueFlush(Instant now) {
    return !members.isEmpty() && nextFlushAt != null && !now.isBefore(nextFlushAt);
  }

  public record FlushResult(AggregationGroup group, List<Alert> alerts) {}

  /**
   * Rule 8: the flushed batch is the group's full current membership, not a delta. Resolved
   * alerts are dropped afterwards so a dead alert is not carried into every future flush; the
   * group's next due time moves to {@code now + groupInterval}.
   */
  public FlushResult flush(Instant now) {
    var snapshot = List.copyOf(members.values());
    var kept = new HashMap<String, Alert>();
    members.forEach((k, a) -> {
      if (!a.isResolved(now)) kept.put(k, a);
    });
    var next = new AggregationGroup(groupKey, groupLabels, groupWait, groupInterval, Map.copyOf(kept), now.plus(groupInterval));
    return new FlushResult(next, snapshot);
  }
}
