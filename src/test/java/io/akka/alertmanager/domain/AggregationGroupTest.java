package io.akka.alertmanager.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 7, 8, 9 — question-log #7, #9. */
public class AggregationGroupTest {

  private static final Duration GROUP_WAIT = Duration.ofSeconds(30);
  private static final Duration GROUP_INTERVAL = Duration.ofSeconds(10);

  @Test
  public void firstFlushWaitsGroupWait() {
    var now = Instant.now();
    var group = AggregationGroup.empty("g1", Map.of(), GROUP_WAIT, GROUP_INTERVAL);
    var alert = new Alert(Map.of("alertname", "Down"), now, Optional.empty());
    group = group.onInsert(alert, now);

    assertThat(group.nextFlushAt()).isEqualTo(now.plus(GROUP_WAIT));
    assertThat(group.dueFlush(now)).isFalse();
    assertThat(group.dueFlush(now.plus(GROUP_WAIT))).isTrue();
  }

  @Test
  public void staleAlertFlushesImmediately() {
    var now = Instant.now();
    var group = AggregationGroup.empty("g1", Map.of(), GROUP_WAIT, GROUP_INTERVAL);
    // startsAt is already older than GroupWait relative to now.
    var staleAlert = new Alert(Map.of("alertname", "Down"), now.minus(GROUP_WAIT).minusSeconds(1), Optional.empty());
    group = group.onInsert(staleAlert, now);

    assertThat(group.nextFlushAt()).isEqualTo(now);
    assertThat(group.dueFlush(now)).isTrue();
  }

  @Test
  public void laterInsertDoesNotResetAnAlreadyScheduledFlush() {
    var now = Instant.now();
    var group = AggregationGroup.empty("g1", Map.of(), GROUP_WAIT, GROUP_INTERVAL);
    group = group.onInsert(new Alert(Map.of("alertname", "Down"), now, Optional.empty()), now);
    var scheduledAt = group.nextFlushAt();

    var later = now.plusSeconds(5);
    group = group.onInsert(new Alert(Map.of("alertname", "Down", "instance", "b"), later, Optional.empty()), later);

    assertThat(group.nextFlushAt()).isEqualTo(scheduledAt);
  }

  @Test
  public void laterFlushesWaitGroupInterval() {
    var now = Instant.now();
    var group = AggregationGroup.empty("g1", Map.of(), GROUP_WAIT, GROUP_INTERVAL);
    group = group.onInsert(new Alert(Map.of("alertname", "Down"), now, Optional.empty()), now);

    var flushTime = group.nextFlushAt();
    var result = group.flush(flushTime);

    assertThat(result.group().nextFlushAt()).isEqualTo(flushTime.plus(GROUP_INTERVAL));
  }

  @Test
  public void flushCarriesFullMembership() {
    var now = Instant.now();
    var group = AggregationGroup.empty("g1", Map.of(), GROUP_WAIT, GROUP_INTERVAL);
    group = group.onInsert(new Alert(Map.of("alertname", "Down", "instance", "a"), now, Optional.empty()), now);
    group = group.onInsert(new Alert(Map.of("alertname", "Down", "instance", "b"), now, Optional.empty()), now);

    var result = group.flush(group.nextFlushAt());
    assertThat(result.alerts()).hasSize(2);
  }

  @Test
  public void resolvedMembersAreDroppedAfterFlush() {
    var now = Instant.now();
    var group = AggregationGroup.empty("g1", Map.of(), GROUP_WAIT, GROUP_INTERVAL);
    var resolved = new Alert(Map.of("alertname", "Down", "instance", "a"), now.minusSeconds(60), Optional.of(now.minusSeconds(1)));
    group = group.onInsert(resolved, now.minusSeconds(60));

    var result = group.flush(now);
    assertThat(result.group().members()).isEmpty();
  }
}
