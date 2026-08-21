package io.akka.alertmanager;

import io.akka.alertmanager.domain.Alert;
import io.akka.alertmanager.domain.InhibitRule;
import io.akka.alertmanager.domain.InhibitionBook;
import io.akka.alertmanager.domain.MatchType;
import io.akka.alertmanager.domain.Matcher;
import io.akka.alertmanager.domain.Matchers;
import io.akka.alertmanager.domain.Silence;
import io.akka.alertmanager.domain.SilenceBook;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Times the same three decision functions {@code bench/speed_source/main.go} times against
 * the real source, printed as ns/op so {@code bench/REPORT.md} can put the two side by side.
 * Not an assertion — a measurement, run by hand and transcribed (`mvn test
 * -Dtest=BenchAnswersTest`).
 */
public class BenchAnswersTest {

  private static final int ITERATIONS = 200_000;

  private static long timeIt(Supplier<?> f) {
    for (int i = 0; i < 2_000; i++) f.get();
    var start = System.nanoTime();
    for (int i = 0; i < ITERATIONS; i++) f.get();
    return (System.nanoTime() - start) / ITERATIONS;
  }

  @Test
  public void printTimings() {
    var matcher = new Matcher("severity", "critical", MatchType.EQUAL);
    var lset = Map.of("severity", "critical", "alertname", "Down");
    var matcherNs = timeIt(() -> matcher.matches(lset));

    var now = Instant.now();
    var book = SilenceBook.EMPTY.add("s1", new Matchers(List.of(new Matcher("sev", "page", MatchType.EQUAL))),
        now.plusSeconds(60), now.plusSeconds(3600), now);
    var silenceNs = timeIt(() -> book.isMuted(Map.of("sev", "page"), now));

    var rule = new InhibitRule("r", new Matchers(List.of(new Matcher("sev", "critical", MatchType.EQUAL))),
        new Matchers(List.of(new Matcher("sev", "warning", MatchType.EQUAL))), Set.of("alertname"));
    var inh = InhibitionBook.withRules(List.of(rule))
        .onAlert(new Alert(Map.of("alertname", "Flapping", "sev", "critical", "job", "a"), now, Optional.of(now.plusSeconds(3600))));
    var target = Map.of("alertname", "Flapping", "sev", "warning", "job", "a");
    var inhibitionNs = timeIt(() -> inh.isInhibited(target, now));

    System.out.println("{");
    System.out.println("  \"matcher_matches_ns\": " + matcherNs + ",");
    System.out.println("  \"silence_mutes_ns\": " + silenceNs + ",");
    System.out.println("  \"inhibition_mutes_ns\": " + inhibitionNs);
    System.out.println("}");
  }
}
