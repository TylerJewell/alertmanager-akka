package io.akka.alertmanager.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The whole capability's state — SPEC-001 §2: silences, the inhibition rule set and cache,
 * and every aggregation group in play, plus the one route's grouping options. Everything here
 * is pure; {@code AlertingEntity} is the only place a mutation is persisted.
 */
public record AlertingState(
    SilenceBook silences,
    InhibitionBook inhibitions,
    Map<String, AggregationGroup> groups,
    Set<String> groupBy,
    Duration groupWait,
    Duration groupInterval) {

  public static AlertingState initial(
      List<InhibitRule> rules, Set<String> groupBy, Duration groupWait, Duration groupInterval) {
    return new AlertingState(SilenceBook.EMPTY, InhibitionBook.withRules(rules), Map.of(), groupBy, groupWait, groupInterval);
  }

  public Map<String, String> groupLabelsFor(Map<String, String> labels) {
    return labels.entrySet().stream()
        .filter(e -> groupBy.contains(e.getKey()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  public record SubmitResult(AlertingState state, String groupKey, Instant nextFlushAt) {}

  /** Rule 9: every submitted alert is grouped, independent of whether it is currently muted. */
  public SubmitResult onAlert(Alert alert, Instant now) {
    var nextInhibitions = inhibitions.onAlert(alert);
    var groupLabels = groupLabelsFor(alert.labels());
    var key = AggregationGroup.fingerprint(groupLabels);
    var group = groups.getOrDefault(key, AggregationGroup.empty(key, groupLabels, groupWait, groupInterval));
    var nextGroup = group.onInsert(alert, now);

    var nextGroups = new HashMap<>(groups);
    nextGroups.put(key, nextGroup);

    var nextState = new AlertingState(silences, nextInhibitions, Map.copyOf(nextGroups), groupBy, groupWait, groupInterval);
    return new SubmitResult(nextState, key, nextGroup.nextFlushAt());
  }

  /** Rule 9: silenced OR inhibited, both re-evaluated against {@code now} on every call. */
  public boolean isMuted(Map<String, String> labels, Instant now) {
    return silences.isMuted(labels, now) || inhibitions.isInhibited(labels, now);
  }

  public AlertingState addSilence(String id, Matchers matchers, Instant startsAt, Instant endsAt, Instant now) {
    return new AlertingState(silences.add(id, matchers, startsAt, endsAt, now), inhibitions, groups, groupBy, groupWait, groupInterval);
  }

  public AlertingState removeSilence(String id) {
    return new AlertingState(silences.remove(id), inhibitions, groups, groupBy, groupWait, groupInterval);
  }

  public record FlushOutcome(AlertingState state, List<Alert> alerts) {}

  public FlushOutcome flushGroup(String groupKey, Instant now) {
    var group = groups.get(groupKey);
    if (group == null || !group.dueFlush(now)) {
      return new FlushOutcome(this, List.of());
    }
    var result = group.flush(now);
    var nextGroups = new HashMap<>(groups);
    // An emptied group is dropped rather than kept as a dormant entry — mirrors the source's
    // own maintenance pass, which deletes a destroyed (memberless) group rather than leaving
    // it in the map forever (dispatch.go doMaintenance).
    if (result.group().members().isEmpty()) {
      nextGroups.remove(groupKey);
    } else {
      nextGroups.put(groupKey, result.group());
    }
    var nextState = new AlertingState(silences, inhibitions, Map.copyOf(nextGroups), groupBy, groupWait, groupInterval);
    return new FlushOutcome(nextState, result.alerts());
  }

  /** Replays one persisted event. Every event's own {@code now} field drives its decision. */
  public AlertingState onEvent(AlertingEvent event) {
    return switch (event) {
      case AlertingEvent.AlertSubmitted e -> onAlert(new Alert(e.labels(), e.startsAt(), Optional.ofNullable(e.endsAt())), e.now()).state();
      case AlertingEvent.SilenceCreated e -> addSilence(e.id(), e.matchers(), e.startsAt(), e.endsAt(), e.now());
      case AlertingEvent.SilenceDeleted e -> removeSilence(e.id());
      case AlertingEvent.GroupFlushed e -> flushGroup(e.groupKey(), e.now()).state();
    };
  }
}
