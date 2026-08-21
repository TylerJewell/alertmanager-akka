package io.akka.alertmanager.domain;

import java.util.Set;

/**
 * A source class of alerts that mutes a target class — SPEC-001 §2, §3 rules 5, 6. {@code
 * equal} names the labels whose values must agree between the source and target alert.
 */
public record InhibitRule(String name, Matchers sourceMatchers, Matchers targetMatchers, Set<String> equal) {}
