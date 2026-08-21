package io.akka.alertmanager.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 5, 6 — question-log #5, #6. */
public class InhibitionBookTest {

  private static InhibitRule criticalInhibitsWarning() {
    return new InhibitRule(
        "critical-inhibits-warning",
        new Matchers(List.of(new Matcher("sev", "critical", MatchType.EQUAL))),
        new Matchers(List.of(new Matcher("sev", "warning", MatchType.EQUAL))),
        Set.of("alertname"));
  }

  @Test
  public void distinctSourceInhibits() {
    var now = Instant.now();
    var book = InhibitionBook.withRules(List.of(criticalInhibitsWarning()));
    var source = new Alert(Map.of("alertname", "Flapping", "sev", "critical", "job", "a"), now, Optional.of(now.plusSeconds(3600)));
    book = book.onAlert(source);

    var targetLabels = Map.of("alertname", "Flapping", "sev", "warning", "job", "a");
    assertThat(book.isInhibited(targetLabels, now)).isTrue();
  }

  @Test
  public void twoSidedMatchDoesNotSelfInhibit() {
    var now = Instant.now();
    var selfRule =
        new InhibitRule(
            "self",
            new Matchers(List.of(new Matcher("sev", "critical", MatchType.EQUAL))),
            new Matchers(List.of(new Matcher("sev", "critical", MatchType.EQUAL))),
            Set.of("alertname"));
    var book = InhibitionBook.withRules(List.of(selfRule));
    var alert = new Alert(Map.of("alertname", "Flapping", "sev", "critical", "job", "a"), now, Optional.of(now.plusSeconds(3600)));
    book = book.onAlert(alert);

    assertThat(book.isInhibited(alert.labels(), now)).isFalse();
  }

  /**
   * Question-log A2: the source's own tie-break (inhibit.go's {@code updateIndex}) is
   * arrival-order-dependent when two candidate source alerts share the same {@code EndsAt}
   * (confirmed by {@code bench/run_order_probe.py} against the real package — 2 distinct
   * winners across 6 delivery orders). This port deliberately diverges: ties are broken by
   * each candidate's own fingerprint (a key that does not depend on arrival order) so the
   * same three alerts always resolve to the same winner no matter what order they arrived in.
   */
  @Test
  public void tiedEndsAtTieBreakIsOrderIndependent() {
    var now = Instant.now();
    var rule = criticalInhibitsWarning();
    var tiedEndsAt = now.plusSeconds(3600);
    var a = new Alert(Map.of("alertname", "Flapping", "sev", "critical", "job", "a"), now, Optional.of(tiedEndsAt));
    var b = new Alert(Map.of("alertname", "Flapping", "sev", "critical", "job", "b"), now, Optional.of(now.plusSeconds(1800)));
    var c = new Alert(Map.of("alertname", "Flapping", "sev", "critical", "job", "c"), now, Optional.of(tiedEndsAt));
    var targetLabels = Map.of("alertname", "Flapping", "sev", "warning");

    var insertedABC = InhibitionBook.withRules(List.of(rule)).onAlert(a).onAlert(b).onAlert(c);
    var insertedCBA = InhibitionBook.withRules(List.of(rule)).onAlert(c).onAlert(b).onAlert(a);

    var winnerABC = insertedABC.findSource(rule, targetLabels, now).map(w -> w.labels().get("job"));
    var winnerCBA = insertedCBA.findSource(rule, targetLabels, now).map(w -> w.labels().get("job"));
    assertThat(winnerABC).isEqualTo(winnerCBA);
  }

  @Test
  public void resolvedSourceNoLongerInhibits() {
    var now = Instant.now();
    var book = InhibitionBook.withRules(List.of(criticalInhibitsWarning()));
    var source = new Alert(Map.of("alertname", "Flapping", "sev", "critical", "job", "a"), now.minusSeconds(120), Optional.of(now.minusSeconds(60)));
    book = book.onAlert(source);

    var targetLabels = Map.of("alertname", "Flapping", "sev", "warning", "job", "a");
    assertThat(book.isInhibited(targetLabels, now)).isFalse();
  }
}
