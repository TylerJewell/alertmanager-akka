package io.akka.alertmanager.domain;

import java.util.List;
import java.util.Map;

/** AND of every matcher in the list — vacuously true for an empty list. */
public record Matchers(List<Matcher> matchers) {

  public static final Matchers NONE = new Matchers(List.of());

  public boolean matches(Map<String, String> labels) {
    return matchers.stream().allMatch(m -> m.matches(labels));
  }
}
