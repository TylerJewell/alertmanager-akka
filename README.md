# alertmanager-akka

Decides, at any moment, whether an alert is muted and which group of alerts it belongs
to — the same decision Prometheus Alertmanager makes before it ever sends a
notification.

A port of [prometheus/alertmanager](https://github.com/prometheus/alertmanager) onto
**Akka**, built with **Akka Specify**.

---

## Where it came from

prometheus/alertmanager is the system Prometheus hands firing alerts to: it decides
which alerts to bundle into one notification, which to hold back because someone
silenced them, and which to hold back because a more severe alert already covers the
same problem. It was ported to derive a specification format precise enough to
regenerate a system on a different stack — the port is the vehicle, the specification is
the deliverable.

Only one part of alertmanager is rebuilt here: grouping, inhibition and silencing — the
three checks that run before a notification is ever sent, all of them answered
differently depending on what time it is when they're asked. The specifications the port
was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `alertmanager-port/`.

---

## prometheus/alertmanager → this port

📉 2,911 Go lines (three whole source files) → **440 Java lines**<br>
📁 3 files → **14 files**<br>
⚡ 1,326 nanoseconds → **96 nanoseconds** to decide whether an alert is silenced<br>
⚡ 1,117 nanoseconds → **613 nanoseconds** to decide whether an alert is inhibited<br>
🧪 0 tests broken on purpose → **25 tests, 9 rules confirmed against the running original**<br>
🔀 2 different winners across 6 delivery orders → **1 winner, every order**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/alertmanager-port/bench/REPORT.md).

---

## What it took to build

⏱️ **0.6 hours** from the first command to the published repository, **0.6** of them active<br>
💬 **365** exchanges with the model<br>
✍️ **238,288** tokens written by the model, **78,847,027** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **25** tests

```bash
python toolkit/tokens.py --port alertmanager    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

An alert arrives as a set of labels, a start time, and an optional end time. Three
questions get asked about it, and every one of them can have a different answer a moment
later purely because time passed:

- **Is it silenced?** A silence is a set of label rules with a start and end time. Only
  while the current time is between those two times, inclusive on both ends, does a
  matching silence mute the alert — before its start time it is tracked but does
  nothing, and after its end time it is gone.
- **Is it inhibited?** A rule can say "a firing alert matching X mutes any alert matching
  Y, as long as they agree on certain labels." An alert can never inhibit itself, even
  when it happens to satisfy both sides of the same rule.
- **Which group does it belong to, and when does that group next notify?** Alerts
  sharing the same group labels are bundled together. A new group waits before its first
  notification; every one after that waits a shorter interval, and always carries every
  alert currently in the group, not just the new ones.

Nothing here calls a language model. The work is a set of decisions over data already
received; what produced the alerts, and how they get delivered once a decision is made,
belongs to a different part of alertmanager.

---

## Design decisions

**Silencing, inhibition and grouping are pure functions of the current time.** Nothing
is decided once and cached past when the clock would have changed the answer — asking
the same question a moment later can get a different answer, and that is treated as
correct rather than as something to guard against.

**Ties are broken the same way no matter what order things arrived in.** When two
candidate alerts are otherwise equally good at inhibiting a third, this port always
picks the same one of the two, using something about the alerts themselves rather than
which one happened to arrive first.

**One entity holds everything.** Every silence, every inhibition rule, and every
aggregation group lives in one place, the same way one alertmanager process holds all of
them for the whole system it is protecting.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/alertmanager-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9031.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9031**.

### Try it

```bash
# submit an alert
curl -X POST localhost:9031/alerting/alerts -H 'Content-Type: application/json' \
  -d '{"labels":{"alertname":"Down","severity":"warning"},"startsAt":"2026-01-01T00:00:00Z"}'

# silence it
curl -X POST localhost:9031/alerting/silences -H 'Content-Type: application/json' \
  -d '{"matchers":[{"name":"alertname","value":"Down","type":"EQUAL"}],"endsAt":"2026-01-01T01:00:00Z"}'

# ask whether it is muted
curl -X POST localhost:9031/alerting/muted -H 'Content-Type: application/json' \
  -d '{"labels":{"alertname":"Down","severity":"warning"}}'
```

---

## Configuration

There are no environment variables. The one setting is the port it listens on, written
in `src/main/resources/application.conf`:

```
akka.javasdk.dev-mode.http-port = 9031
```

---

## Where it differs from prometheus/alertmanager

Everything not listed here behaves the same way on purpose, including the parts that
look like mistakes.

- **Which alert wins when two tie for inhibiting a third.** alertmanager decides this
  incrementally as alerts arrive, replacing its current pick whenever a newly arrived
  alert is at least as strong a candidate as the one it already has — which means that
  when two alerts tie exactly, whichever one alertmanager saw most recently wins, and
  running the same three alerts through in a different order can produce a different
  winner. This port instead breaks a tie using something about the tied alerts
  themselves, so the same three alerts always produce the same winner no matter what
  order they arrived in — a decision procedure whose answer depends on delivery timing
  between otherwise-identical inputs was judged worse to carry forward than to fix.
- **Whether a group that has flushed down to zero members is kept around.** alertmanager
  runs a periodic sweep that deletes an empty group. This port drops a group the moment
  a flush leaves it with no members, rather than waiting for a separate sweep.
- **How long a silence or a candidate alert for inhibition is remembered.** alertmanager
  runs its own periodic garbage collection over both. This port keeps every silence and
  every alert it has ever seen for as long as the service runs, with no eviction — `not
  checked` against any particular volume, because no sustained run at production alert
  rates was tried against either side.
- **Multi-level routing, notification delivery, the gossip-replicated HA cluster, and
  on-disk snapshots.** None of these are rebuilt here. This port answers the grouping,
  inhibition and silencing questions only; what happens after a group decides to notify,
  and how multiple alertmanager replicas agree with each other, are both a different job.

---

## Licence

prometheus/alertmanager is Apache License 2.0, © The Prometheus Authors. This port
reimplements the behaviour without copied source; see
[`ACKNOWLEDGEMENTS.md`](ACKNOWLEDGEMENTS.md).
