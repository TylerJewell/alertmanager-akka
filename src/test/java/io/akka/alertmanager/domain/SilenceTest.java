package io.akka.alertmanager.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rule 1 — question-log #1. */
public class SilenceTest {

  private static final Matchers ANY = new Matchers(List.of(new Matcher("sev", "page", MatchType.EQUAL)));

  @Test
  public void activeAtStartsAt() {
    var now = Instant.now();
    var sil = new Silence("s1", ANY, now, now.plusSeconds(3600));
    assertThat(sil.stateAt(now)).isEqualTo(SilenceState.ACTIVE);
  }

  @Test
  public void activeAtEndsAt() {
    var now = Instant.now();
    var ends = now.plusSeconds(3600);
    var sil = new Silence("s1", ANY, now, ends);
    assertThat(sil.stateAt(ends)).isEqualTo(SilenceState.ACTIVE);
  }

  @Test
  public void pendingBeforeStarts() {
    var starts = Instant.now().plusSeconds(60);
    var sil = new Silence("s1", ANY, starts, starts.plusSeconds(3600));
    assertThat(sil.stateAt(starts.minusNanos(1))).isEqualTo(SilenceState.PENDING);
  }

  @Test
  public void expiredAfterEnds() {
    var now = Instant.now();
    var ends = now.plusSeconds(3600);
    var sil = new Silence("s1", ANY, now, ends);
    assertThat(sil.stateAt(ends.plusNanos(1))).isEqualTo(SilenceState.EXPIRED);
  }
}
