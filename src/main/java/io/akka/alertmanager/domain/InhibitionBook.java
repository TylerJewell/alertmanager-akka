package io.akka.alertmanager.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The rule set plus every alert seen, kept as a candidate source — SPEC-001 §2, §3 rules 5,
 * 6. Pure and immutable, mirroring {@code SilenceBook}.
 */
public record InhibitionBook(List<InhibitRule> rules, List<Alert> knownAlerts) {

  public static InhibitionBook withRules(List<InhibitRule> rules) {
    return new InhibitionBook(rules, List.of());
  }

  /** Replaces any prior alert with the same labels — the cache tracks one entry per alert. */
  public InhibitionBook onAlert(Alert alert) {
    var next = knownAlerts.stream().filter(a -> !a.labels().equals(alert.labels())).collect(Collectors.toCollection(ArrayList::new));
    next.add(alert);
    return new InhibitionBook(rules, List.copyOf(next));
  }

  /**
   * Rules 5, 6. Evaluated in rule-declaration order (question-log A1); the first rule whose
   * target matches and has a live, non-self source wins.
   */
  public boolean isInhibited(Map<String, String> labels, Instant now) {
    for (var rule : rules) {
      if (!rule.targetMatchers().matches(labels)) continue;
      var candidate = findSource(rule, labels, now);
      if (candidate.isEmpty()) continue;

      // Rule 6: if this label set could itself act as a source for this rule, and the only
      // matching source we found is itself a valid target, an alert must not count as its
      // own inhibitor.
      boolean couldBeOwnSource = rule.sourceMatchers().matches(labels);
      if (couldBeOwnSource && rule.targetMatchers().matches(candidate.get().labels())) {
        continue;
      }
      return true;
    }
    return false;
  }

  /**
   * Package-visible so {@code InhibitionBookTest} can assert the tie-break is
   * order-independent (question-log A2) without going through {@link #isInhibited}, which
   * only reports whether a source exists, not which one won.
   *
   * <p>Ties on {@code endsAt} are broken by the candidate's own fingerprint (its sorted
   * label string), a key that does not depend on arrival order — deliberately not the
   * source's own rule, which replays each arrival against whatever is currently indexed
   * and so keeps whichever tied alert arrived last (question-log A2, confirmed
   * order-dependent by {@code bench/run_order_probe.py} against the real package).
   */
  Optional<Alert> findSource(InhibitRule rule, Map<String, String> targetLabels, Instant now) {
    var wantEqual = equalValues(rule.equal(), targetLabels);
    Alert best = null;
    for (var a : knownAlerts) {
      if (!a.isFiring(now)) continue;
      if (!rule.sourceMatchers().matches(a.labels())) continue;
      if (!equalValues(rule.equal(), a.labels()).equals(wantEqual)) continue;
      if (best == null || wins(a, best)) best = a;
    }
    return Optional.ofNullable(best);
  }

  private static Map<String, String> equalValues(Set<String> names, Map<String, String> labels) {
    return names.stream().collect(Collectors.toMap(n -> n, n -> labels.getOrDefault(n, "")));
  }

  private static boolean wins(Alert candidate, Alert incumbent) {
    int byEndsAt = compareEndsAt(candidate, incumbent);
    if (byEndsAt != 0) return byEndsAt > 0;
    return AggregationGroup.fingerprint(candidate.labels()).compareTo(AggregationGroup.fingerprint(incumbent.labels())) < 0;
  }

  /** No {@code endsAt} means "never resolves," i.e. later than any concrete timestamp. */
  private static int compareEndsAt(Alert a, Alert b) {
    if (a.endsAt().isEmpty() && b.endsAt().isEmpty()) return 0;
    if (a.endsAt().isEmpty()) return 1;
    if (b.endsAt().isEmpty()) return -1;
    return a.endsAt().get().compareTo(b.endsAt().get());
  }
}
