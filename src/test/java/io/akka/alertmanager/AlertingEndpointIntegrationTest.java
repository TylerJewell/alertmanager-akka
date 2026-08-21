package io.akka.alertmanager;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.alertmanager.api.AlertingEndpoint;
import io.akka.alertmanager.api.AlertingEndpoint.AlertRequest;
import io.akka.alertmanager.api.AlertingEndpoint.MatcherRequest;
import io.akka.alertmanager.api.AlertingEndpoint.MuteQuery;
import io.akka.alertmanager.api.AlertingEndpoint.SilenceRequest;
import io.akka.alertmanager.api.AlertingEndpoint.SubmitResponse;
import io.akka.alertmanager.domain.MatchType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The HTTP surface, end to end against a running runtime — the whole capability driven the way
 * a caller reaches it (see {@code gui/manifest.json}). Every alert here carries a unique
 * {@code alertname} so tests never share an aggregation group or interfere with each other's
 * silences.
 */
public class AlertingEndpointIntegrationTest extends TestKitSupport {

  private String uniqueAlertName(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /** Event-sourced writes are visible to a subsequent read once the persist completes, but
   * this polls briefly rather than assuming synchronous consistency across the HTTP round trip. */
  private void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
    var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(100);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private SubmitResponse submit(Map<String, String> labels, Instant startsAt, Instant endsAt) {
    return httpClient
        .POST("/alerting/alerts")
        .withRequestBody(new AlertRequest(labels, startsAt, endsAt))
        .responseBodyAs(SubmitResponse.class)
        .invoke()
        .body();
  }

  private boolean isMuted(Map<String, String> labels) {
    return httpClient
        .POST("/alerting/muted")
        .withRequestBody(new MuteQuery(labels))
        .responseBodyAs(Boolean.class)
        .invoke()
        .body();
  }

  @Test
  public void silencedAndInhibitedBothSuppress() throws InterruptedException {
    var now = Instant.now();

    // Not muted before any silence or inhibiting alert exists.
    var alertName = uniqueAlertName("Down");
    var labels = Map.of("alertname", alertName, "severity", "warning");
    submit(labels, now, null);
    assertThat(isMuted(labels)).isFalse();

    // A silence matching this alertname mutes it.
    httpClient
        .POST("/alerting/silences")
        .withRequestBody(
            new SilenceRequest(
                List.of(new MatcherRequest("alertname", alertName, MatchType.EQUAL)),
                now,
                now.plus(Duration.ofHours(1))))
        .invoke();
    awaitTrue(() -> isMuted(labels));

    // A different alert, muted only via inhibition (critical of the same alertname firing).
    var inhibitedName = uniqueAlertName("HighLatency");
    var criticalLabels = Map.of("alertname", inhibitedName, "severity", "critical");
    var warningLabels = Map.of("alertname", inhibitedName, "severity", "warning");
    submit(warningLabels, now, null);
    assertThat(isMuted(warningLabels)).isFalse();

    submit(criticalLabels, now, now.plus(Duration.ofHours(1)));
    awaitTrue(() -> isMuted(warningLabels));
  }

  @Test
  public void submittingGroupsByAlertNameAndSchedulesFirstFlush() {
    var now = Instant.now();
    var alertName = uniqueAlertName("Flapping");
    var labels = Map.of("alertname", alertName, "instance", "a");

    var result = submit(labels, now, null);

    assertThat(result.groupKey()).contains("alertname=" + alertName);
    assertThat(result.nextFlushAt()).isAfterOrEqualTo(now);
  }

  @Test
  public void anEmptyLabelSetIsRejectedWithABadRequest() {
    var response =
        httpClient
            .POST("/alerting/alerts")
            .withRequestBody(new AlertRequest(Map.of(), Instant.now(), null))
            .invoke();
    assertThat(response.httpResponse().status().intValue()).isEqualTo(400);
  }
}
