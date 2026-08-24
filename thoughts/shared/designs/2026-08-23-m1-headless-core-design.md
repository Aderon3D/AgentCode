---
date: 2026-08-23
topic: "M1 Headless Core"
status: draft
---

# M1 — Headless Core: Design

## Problem Statement

M0.5 proves the architecture spine *thinks* end-to-end, but only against
in-memory fakes: `ProcessRunner` is a throwing stub (`StubProcessRunner`) and
`FileSystemProvider` is `InMemoryFileSystem`; the WAL is a volatile in-memory
list. M1 ("The Bedrock") must make the spine actually **act on a real
repository and real filesystem** and **persist the WAL durably**, establishing
the foundation before multi-agent concurrency (M2).

Today the only non-fake path through the system is the scripted `LlmProvider`
in the bootstrap demo. The two storage/IO boundaries that gate real behavior
are stubbed.

## Constraints

- **Green gate must hold.** `:shared:testAndroidHostTest` runs on the JVM host
  test target. Any NDK cinterop / Shizuku binder / device-only code **cannot**
  execute there, so such code must be `expect`/`actual` with JVM-safe fakes.
  Host tests continue to use in-memory fakes.
- **No Android/native imports in `commonMain`** — the M0.5 hard rule carries
  forward into M1.
- Minimal and dependency-light. No new Gradle modules unless they earn their
  place with real content.
- The deterministic demo contract (`MissionControlBootstrap.runDemo()` returns
  a `Timeline` and the four guarantees) must remain intact and green.

## Approach

**Chosen: A — Real I/O slice, stays testable.**

Replace the two stubs with **real implementations behind the existing
interfaces**, make the WAL **durable**, and keep everything JVM-host-testable.
Defer libgit2 NDK, Shizuku elevation, SQLDelight, and the full multi-module
Gradle promotion to later M1 sub-phases (M1.2 / M1.3).

**Why A over the alternatives:**

- **B (full doc M1: NDK libgit2 + Shizuku + SQLDelight + module promotion)** —
  infeasible on the host gate, requires an NDK toolchain and a device/emulator
  in CI, massive surface area, and zero incremental value as a first step.
  Rejected as the opening move.
- **C (module-promotion-only restructure)** — pure reshuffling with no
  functional gain and high churn risk. Rejected.
- **A** delivers the actual bedrock (real git + real FS + durable WAL) in one
  mergeable, testable step and *de-risks* B later.

## Architecture

- Interfaces stay in `commonMain` under the `workspace` package:
  `FileSystemProvider`, `ProcessRunner`, `WalStore`. As shipped, `FileSystemProvider`
  and `ProcessRunner` already use `Result`-based contracts (`read`/`write` return
  `Result`, `run` returns `Result<String>`); error typing lives in the sealed
  `FileError` hierarchy. Further widening (`applyPatch`/`walkTree`/`delete`) remains
  future work.
- Real backends are ordinary classes in `androidMain` (not KMP `actual`s): a real
  `git` CLI driven through `ProcessRunner`, and real file IO via `java.io`. The
  JVM host test target keeps `InMemoryFileSystem` + `StubProcessRunner`.
- `WalStore` gains a `FileBackedWalStore` (append-only text log on real FS) for
  Android; `InMemoryWalStore` remains for tests.
- `MissionControlBootstrap.runDemo()` continues to use fakes so the
  deterministic contract is unchanged.

## Components

- **`RealFileSystem`** (androidMain actual) — implements read/write/exists
  against actual device paths with safe error wrapping.
- **`GitProcessRunner`** (androidMain actual) — shells `git` commands and
  parses stdout into tool results.
- **`FileBackedWalStore`** — durable append-only WAL on real FS; appends are
  atomic; unparseable lines are skipped via `runCatching` (same resilience as
  today's `AgentEventJournal`).
- **Retained as fakes:** `InMemoryFileSystem`, `StubProcessRunner`,
  `InMemoryWalStore`.
- **Deferred to M1.2+:** `LibGit2Engine` expect/actual, `WorktreeManager`,
  `StartupSelfHealer`, Shizuku elevation, Gradle multi-module promotion.

## Data Flow

Unchanged control path: `AgentOrchestrator` → `McpHost` → `AgentTool` →
`FileSystemProvider` / `ProcessRunner`. Only the backend swaps from in-memory
to real. WAL `append` now persists to a file; `replay` reconstructs state after
a restart — proving the M0.5 recovery invariant against a *real* store rather
than a list.

## Error Handling

- Tool dispatch already catches exceptions and returns a `ToolResult` failure;
  preserve that boundary.
- WAL write failures must not corrupt replay: atomic append, skip unparseable
  lines on read.
- (M1.2) Migrate `FileSystemProvider` / `ProcessRunner` to `Result` + sealed
  `FileError` per doc §2.5 — not required for the first slice.

## Testing Strategy

- **Gate stays green:** existing `MissionControlBootstrapTest`,
  `TelemetryEngineTest`, router, and streaming-JSON tests are untouched
  (in-memory fakes).
- **New integration tests on the JVM host target** (no device needed):
  `RealFileSystem` exercised against a temp directory; `GitProcessRunner`
  against a temp `git` repo — proves real IO without any NDK.
- **`FileBackedWalStore` round-trip:** write events, simulate a restart, assert
  `replay` reconstructs identical state (the same guarantee M0.5 tests).
- Deterministic demo contract preserved.

## Open Questions

1. Widen `FileSystemProvider` / `ProcessRunner` signatures now or in M1.2? →
   **Recommend minimal change first**, widen only when real features need it.
2. Gradle multi-module promotion now or keep `:shared`? → **Keep `:shared`**;
   promote only when real modules have substance.
3. libgit2 NDK vs `git` CLI? → **Start with `git` CLI** (zero native); add NDK
   only if CLI proves too slow or limited.


