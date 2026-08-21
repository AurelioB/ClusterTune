# Privileged execution architecture

ClusterTune performs privileged work through one long-lived Binder host. The app-side resolver
selects how that host is started, while all sysfs discovery, mutation, permission changes,
verification, and rollback stay inside the host process.

## Method order

1. **PServer** (`pserver-stdout`, historical id)
   - Probe only checks that `PServerBinder` exists and is alive.
   - Starts the host with one output-disabled Binder transaction.
   - Does not execute per-action commands or provide stdout to the app.

2. **Root shell** (`root-shell`)
   - Probes `su` and starts the same host through a bounded shell process.

The resolver keeps the selected method stable for a host launch and rejects launches when the
selection changes concurrently. Existing persisted `pserver-stdout` settings remain valid even
though stdout is no longer part of the PServer capability.

## Host responsibilities

The host owns capability discovery and an atomic CPU/GPU snapshot. Every apply and minimum repair
is a single transactional Binder request with preflight validation, journaling, rollback, and
post-write readback. A transport loss after mutation is reported as indeterminate and never
falls back to another writer.

The app may use ordinary Android file access for best-effort display values when a host snapshot
is unavailable. That path never performs privileged writes or repairs. Explicit apply and repair
operations fail clearly until a host is available.

## Safety rules

- PServer is lifecycle transport only.
- No privileged per-command stdout or file-output bridge exists.
- Stock writes try the discovered stock ceiling, verify readback, then use the selectable fallback.
- CPU and GPU changes are verified together, with reverse-order rollback on failure.
- Host identity and topology are checked before and after each transaction.
