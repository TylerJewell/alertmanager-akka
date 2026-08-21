package io.akka.alertmanager.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every silence known, active or not — SPEC-001 §3 rules 2, 4. Pure and immutable: every
 * mutation returns a new book, which {@code SilenceEntity} persists as an event.
 */
public record SilenceBook(List<Silence> silences) {

  public static final SilenceBook EMPTY = new SilenceBook(List.of());

  /**
   * Adds a silence, clamping a backdated {@code startsAt} to {@code now} (question-log #4) —
   * a silence can never be created retroactive to when the request was made.
   */
  public SilenceBook add(String id, Matchers matchers, Instant startsAt, Instant endsAt, Instant now) {
    var clampedStart = startsAt.isBefore(now) ? now : startsAt;
    var next = new ArrayList<>(silences);
    next.add(new Silence(id, matchers, clampedStart, endsAt));
    return new SilenceBook(List.copyOf(next));
  }

  public SilenceBook remove(String id) {
    var next = silences.stream().filter(s -> !s.id().equals(id)).toList();
    return new SilenceBook(next);
  }

  /** Rule 2: only an ACTIVE silence mutes; a PENDING one is inert until its startsAt arrives. */
  public boolean isMuted(Map<String, String> labels, Instant now) {
    return silences.stream().anyMatch(s -> s.mutes(labels, now));
  }
}
