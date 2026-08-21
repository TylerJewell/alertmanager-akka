package io.akka.alertmanager.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.alertmanager.domain.AggregationGroup;
import io.akka.alertmanager.domain.Alert;
import io.akka.alertmanager.domain.AlertingEvent;
import io.akka.alertmanager.domain.AlertingState;
import io.akka.alertmanager.domain.InhibitRule;
import io.akka.alertmanager.domain.MatchType;
import io.akka.alertmanager.domain.Matcher;
import io.akka.alertmanager.domain.Matchers;
import io.akka.alertmanager.domain.Silence;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The one running route's silences, inhibition rules, source-alert cache and aggregation
 * groups — SPEC-001. A singleton entity (id {@link #ID}): the source runs one Dispatcher, one
 * Inhibitor and one Silences store per Alertmanager process, and this port keeps that shape
 * rather than sharding it, since the decision procedure it demonstrates is inherently
 * evaluated against one shared view of "what else is currently happening."
 *
 * <p>The inhibition rule and grouping options are fixed at start-up rather than configurable
 * at runtime — the source loads these from a YAML file read once at startup, which this port
 * has no equivalent surface for (out of scope, SPEC-001 §1). The one rule configured here is
 * the source's own canonical example: a critical alert inhibits a warning alert with the same
 * {@code alertname}.
 */
@Component(id = "alerting")
public class AlertingEntity extends EventSourcedEntity<AlertingState, AlertingEvent> {

  public static final String ID = "global";

  private static final List<InhibitRule> RULES =
      List.of(
          new InhibitRule(
              "critical-inhibits-warning",
              new Matchers(List.of(new Matcher("severity", "critical", MatchType.EQUAL))),
              new Matchers(List.of(new Matcher("severity", "warning", MatchType.EQUAL))),
              Set.of("alertname")));
  private static final Set<String> GROUP_BY = Set.of("alertname");
  private static final Duration GROUP_WAIT = Duration.ofSeconds(30);
  private static final Duration GROUP_INTERVAL = Duration.ofMinutes(5);

  @Override
  public AlertingState emptyState() {
    return AlertingState.initial(RULES, GROUP_BY, GROUP_WAIT, GROUP_INTERVAL);
  }

  public record SubmitAlert(Map<String, String> labels, Instant startsAt, Instant endsAt) {}

  public record SubmitResult(String groupKey, Instant nextFlushAt, boolean muted) {}

  /** Rule 9: grouping happens regardless of mute status; {@code muted} is reported alongside it. */
  public Effect<SubmitResult> submitAlert(SubmitAlert cmd) {
    var now = Instant.now();
    var event = new AlertingEvent.AlertSubmitted(cmd.labels(), cmd.startsAt(), cmd.endsAt(), now);
    return effects()
        .persist(event)
        .thenReply(
            state -> {
              var groupKey = AggregationGroup.fingerprint(state.groupLabelsFor(cmd.labels()));
              var group = state.groups().get(groupKey);
              var muted = state.isMuted(cmd.labels(), now);
              return new SubmitResult(groupKey, group.nextFlushAt(), muted);
            });
  }

  public record CreateSilence(List<Matcher> matchers, Instant startsAt, Instant endsAt) {}

  public Effect<String> createSilence(CreateSilence cmd) {
    var now = Instant.now();
    var id = UUID.randomUUID().toString();
    var event = new AlertingEvent.SilenceCreated(id, new Matchers(cmd.matchers()), cmd.startsAt(), cmd.endsAt(), now);
    return effects().persist(event).thenReply(state -> id);
  }

  public Effect<Done> deleteSilence(String id) {
    return effects().persist(new AlertingEvent.SilenceDeleted(id)).thenReply(state -> Done.getInstance());
  }

  public record MuteQuery(Map<String, String> labels) {}

  public ReadOnlyEffect<Boolean> isMuted(MuteQuery query) {
    return effects().reply(currentState().isMuted(query.labels(), Instant.now()));
  }

  public ReadOnlyEffect<List<Silence>> listSilences() {
    return effects().reply(currentState().silences().silences());
  }

  public ReadOnlyEffect<List<AggregationGroup>> listGroups() {
    return effects().reply(List.copyOf(currentState().groups().values()));
  }

  /**
   * Flushes a group if it is due; returns an empty list otherwise. The flushed batch is
   * computed once, against the {@code now} that is also what gets persisted, so the reply and
   * the replayed state agree (Rule 8).
   */
  public Effect<List<Alert>> flushGroup(String groupKey) {
    var now = Instant.now();
    var outcome = currentState().flushGroup(groupKey, now);
    if (outcome.alerts().isEmpty()) {
      return effects().reply(List.of());
    }
    return effects()
        .persist(new AlertingEvent.GroupFlushed(groupKey, now))
        .thenReply(state -> outcome.alerts());
  }

  @Override
  public AlertingState applyEvent(AlertingEvent event) {
    return currentState().onEvent(event);
  }
}
