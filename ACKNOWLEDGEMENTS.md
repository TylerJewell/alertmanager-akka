# Acknowledgements

This project is a port of **[prometheus/alertmanager](https://github.com/prometheus/alertmanager)**,
read and run against a checkout of its `main` branch (2026-08-20).

## Licence

prometheus/alertmanager is **Apache License 2.0**, © The Prometheus Authors. A copy of
that licence is included as `LICENSE-alertmanager`, which Apache-2.0 requires of any work
carrying its material, along with the notice of what was changed that section 4(b) asks
for — this whole file is that notice.

## What was copied

**No source was copied.** No Go file, fragment or expression from alertmanager appears
here; every file in `src/` was written for this project.

One thing was taken across deliberately, and it is a value a caller configures rather
than code: the example inhibition rule this port ships with (`severity: critical`
inhibits `severity: warning` of the same `alertname`) is alertmanager's own canonical
example, used because it is the rule most operators already recognise.

## What is derived

The behaviour is. Every rule in `alertmanager-port/specs/SPEC-001-alertmanager.md` was
established by reading `dispatch/dispatch.go`, `inhibit/inhibit.go`, `silence/silence.go`
and `pkg/labels/matcher.go`, then running the real package — both its own test suite
(`go test ./dispatch/... ./inhibit/... ./silence/... ./pkg/labels/...`) and a set of
additional probes built against it to check claims the existing tests did not directly
cover (exact silence-boundary inclusivity, backdated-silence clamping, and whether the
inhibition tie-break on equal `endsAt` depends on delivery order). The record of what was
checked and how is `alertmanager-port/docs/question-log.md`.

One rule was deliberately **not** taken from the source: alertmanager's inhibition
tie-break on two candidate source alerts sharing the same `endsAt` depends on the order
the alerts arrived in (confirmed by `alertmanager-port/bench/run_order_probe.py` against
the real package). This port breaks that tie with a fixed, arrival-order-independent
rule instead. The reasoning is in `alertmanager-port/specs/SPEC-001-alertmanager.md` §4
and in `README.md` under `Where it differs from prometheus/alertmanager`.

## Also used

- **Akka** (Akka SDK for Java, BSL 1.1) — the platform this port is built on.
- **Go** (`go test`, `go run`) was used to run alertmanager's own test suites and the
  throwaway probes described above; nothing from that toolchain was copied.
