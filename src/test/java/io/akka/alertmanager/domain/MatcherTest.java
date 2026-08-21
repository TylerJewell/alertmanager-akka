package io.akka.alertmanager.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rule 3 — question-log #3. */
public class MatcherTest {

  @Test
  public void notEqualMatchesAbsentLabel() {
    var m = new Matcher("team", "payments", MatchType.NOT_EQUAL);
    assertThat(m.matches(Map.of("alertname", "Down"))).isTrue();
  }

  @Test
  public void equalDoesNotMatchAbsentLabel() {
    var m = new Matcher("team", "payments", MatchType.EQUAL);
    assertThat(m.matches(Map.of("alertname", "Down"))).isFalse();
  }

  @Test
  public void regexpMatchesWholeValue() {
    var m = new Matcher("statuscode", "5..", MatchType.REGEXP);
    assertThat(m.matches(Map.of("statuscode", "503"))).isTrue();
    assertThat(m.matches(Map.of("statuscode", "1503"))).isFalse();
  }
}
