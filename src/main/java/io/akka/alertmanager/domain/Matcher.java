package io.akka.alertmanager.domain;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * One label comparison — SPEC-001 §2. A label the alert does not carry reads as the empty
 * string (question-log #3), so {@code NOT_EQUAL}/{@code NOT_REGEXP} can match an absent label.
 */
public record Matcher(String name, String value, MatchType type) {

  public boolean matches(Map<String, String> labels) {
    String actual = labels.getOrDefault(name, "");
    return switch (type) {
      case EQUAL -> actual.equals(value);
      case NOT_EQUAL -> !actual.equals(value);
      case REGEXP -> Pattern.matches(value, actual);
      case NOT_REGEXP -> !Pattern.matches(value, actual);
    };
  }
}
