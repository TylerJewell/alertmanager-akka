package io.akka.alertmanager.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import io.akka.alertmanager.application.AlertingEntity;
import io.akka.alertmanager.domain.AggregationGroup;
import io.akka.alertmanager.domain.Alert;
import io.akka.alertmanager.domain.MatchType;
import io.akka.alertmanager.domain.Matcher;
import io.akka.alertmanager.domain.Silence;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Submit alerts, create and remove silences, and read back groups and mute status — SPEC-001.
 * This is the port's own reachable surface: the source has no single HTTP endpoint for this,
 * since the same decision is spread across the dispatcher, the inhibitor and the silence API
 * (question-log evidence for each is in {@code docs/question-log.md}).
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/alerting")
public class AlertingEndpoint {

  private final ComponentClient componentClient;

  public AlertingEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public record AlertRequest(Map<String, String> labels, Instant startsAt, Instant endsAt) {}

  public record SubmitResponse(String groupKey, Instant nextFlushAt, boolean muted) {}

  @Post("/alerts")
  public SubmitResponse submitAlert(AlertRequest request) {
    if (request.labels() == null || request.labels().isEmpty()) {
      throw HttpException.badRequest("labels must not be empty");
    }
    var startsAt = request.startsAt() == null ? Instant.now() : request.startsAt();
    var result =
        entity()
            .method(AlertingEntity::submitAlert)
            .invoke(new AlertingEntity.SubmitAlert(request.labels(), startsAt, request.endsAt()));
    return new SubmitResponse(result.groupKey(), result.nextFlushAt(), result.muted());
  }

  public record MuteQuery(Map<String, String> labels) {}

  @Post("/muted")
  public boolean isMuted(MuteQuery query) {
    return entity().method(AlertingEntity::isMuted).invoke(new AlertingEntity.MuteQuery(query.labels()));
  }

  public record MatcherRequest(String name, String value, MatchType type) {}

  public record SilenceRequest(List<MatcherRequest> matchers, Instant startsAt, Instant endsAt) {}

  @Post("/silences")
  public String createSilence(SilenceRequest request) {
    if (request.matchers() == null || request.matchers().isEmpty()) {
      throw HttpException.badRequest("matchers must not be empty");
    }
    if (request.endsAt() == null) {
      throw HttpException.badRequest("endsAt is required");
    }
    var matchers = request.matchers().stream().map(m -> new Matcher(m.name(), m.value(), m.type())).toList();
    var startsAt = request.startsAt() == null ? Instant.now() : request.startsAt();
    return entity()
        .method(AlertingEntity::createSilence)
        .invoke(new AlertingEntity.CreateSilence(matchers, startsAt, request.endsAt()));
  }

  @Delete("/silences/{id}")
  public void deleteSilence(String id) {
    entity().method(AlertingEntity::deleteSilence).invoke(id);
  }

  @Get("/silences")
  public List<Silence> listSilences() {
    return entity().method(AlertingEntity::listSilences).invoke();
  }

  @Get("/groups")
  public List<AggregationGroup> listGroups() {
    return entity().method(AlertingEntity::listGroups).invoke();
  }

  @Post("/groups/{groupKey}/flush")
  public List<Alert> flushGroup(String groupKey) {
    return entity().method(AlertingEntity::flushGroup).invoke(groupKey);
  }

  private akka.javasdk.client.EventSourcedEntityClient entity() {
    return componentClient.forEventSourcedEntity(AlertingEntity.ID);
  }
}
