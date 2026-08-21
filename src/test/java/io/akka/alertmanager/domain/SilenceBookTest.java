package io.akka.alertmanager.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 2, 4 — question-log #2, #4. */
public class SilenceBookTest {

  private static final Matchers SEV_PAGE = new Matchers(List.of(new Matcher("sev", "page", MatchType.EQUAL)));

  @Test
  public void pendingDoesNotMute() {
    var now = Instant.now();
    var book = SilenceBook.EMPTY.add("s1", SEV_PAGE, now.plusSeconds(3600), now.plusSeconds(7200), now);
    assertThat(book.isMuted(Map.of("sev", "page"), now)).isFalse();
  }

  @Test
  public void activeMutes() {
    var now = Instant.now();
    var book = SilenceBook.EMPTY.add("s1", SEV_PAGE, now.minusSeconds(60), now.plusSeconds(3600), now);
    assertThat(book.isMuted(Map.of("sev", "page"), now)).isTrue();
  }

  @Test
  public void clampsBackdatedStart() {
    var now = Instant.now();
    var requestedStart = now.minusSeconds(3600);
    var book = SilenceBook.EMPTY.add("s1", SEV_PAGE, requestedStart, now.plusSeconds(3600), now);
    var stored = book.silences().get(0);
    assertThat(stored.startsAt()).isAfterOrEqualTo(now);
  }

  @Test
  public void removedSilenceNoLongerMutes() {
    var now = Instant.now();
    var book = SilenceBook.EMPTY.add("s1", SEV_PAGE, now.minusSeconds(60), now.plusSeconds(3600), now);
    var afterRemove = book.remove("s1");
    assertThat(afterRemove.isMuted(Map.of("sev", "page"), now)).isFalse();
  }
}
