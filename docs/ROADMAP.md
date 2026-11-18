# Roadmap — AgentCode

**Single source of truth for status.** `docs/Development_Doc.md` is the design
record and does not carry status. Everything else links here.

Last verified: **r42** (2026-11-14), on `main` at `1fa2d32`.

## How this file stays true

1. Status changes land in the **same commit** as `chore(release): rNN` — never in
   a follow-up. If a release exists, this file was already right.
2. Every evidence cell names a repo-relative path or a test.
   `scripts/check-roadmap.sh` fails CI when a cited path disappears, so renames
   cannot silently rot this file.
3. **DONE means three things**: the code exists, a test in the *owning* module
   exercises it, and it is reachable from the app. If the test lives in another
   module, say so — that is a gap, not a pass.
4. Do not add a second status table to README, CONTRIBUTING, or a design doc.
   Status lives here only.

## Milestones

| # | What it proves | Status | Evidence (code / test) |
|---|---|---|---|
| M0.5 | FSM + WAL + telemetry spine runs on the JVM | DONE | `app-ui/src/commonMain/kotlin/com/agent/code/bootstrap/MissionControlBootstrap.kt` · `app-ui/src/commonTest/kotlin/com/agent/code/MissionControlBootstrapTest.kt` · `app-ui/src/commonTest/kotlin/com/agent/code/TelemetryEngineTest.kt` |
| M1 | Real IO, path confinement, durable journal | DONE | `agent-core/src/androidMain/kotlin/com/agent/code/core/journal/FileBackedWalStore.kt` · tested from `app-ui/src/androidHostTest/kotlin/com/agent/code/M1RealIoTest.kt` (wrong module — see gap 1) |
| M2 | Multi-agent concurrency: worktrees, lock coordinator, conflict funnel, tree-sitter | DONE | `workspace-engine/src/commonMain/kotlin/com/agent/code/workspace/WorktreeManager.kt` · `agent-core/src/commonMain/kotlin/com/agent/code/core/lock/TaskLockCoordinator.kt` · `agent-core/src/commonMain/kotlin/com/agent/code/core/lock/SemanticConflictFunnel.kt` · `workspace-engine/src/androidMain/kotlin/com/agent/code/workspace/TreeSitterBackend.kt` · tested from `app-ui/src/androidHostTest/kotlin/com/agent/code/M2ConcurrencyTest.kt` and `app-ui/src/androidHostTest/kotlin/com/agent/code/M2TreeSitterTest.kt` (wrong module) |
| M3 | Dashboard, telemetry stream, cost router, power governor | DONE | `app-ui/src/commonMain/kotlin/com/agent/code/ui/MissionControlPanel.kt` · `app-ui/src/commonMain/kotlin/com/agent/code/ui/CostRoutingPanel.kt` · `app-ui/src/commonTest/kotlin/com/agent/code/TelemetryEngineTest.kt` |
| M4 | Android live testing: FGS, layout oracle, accessibility engine | PARTIAL | `workspace-engine/src/commonMain/kotlin/com/agent/code/ui/GeometricLayoutOracle.kt` · `androidApp/src/main/kotlin/com/agent/code/service/ResilientAgentForegroundService.kt` — no test in `androidApp` |
| M5 | Security: KeyStore vault, git auth, diagnostics | PARTIAL | `agent-core/src/androidMain/kotlin/com/agent/code/core/security/SecureVault.kt` · `workspace-engine/src/commonMain/kotlin/com/agent/code/git/GitAuthWrapper.kt` — zero tests |
| M6 | The brain: opencode child process, run-mode loop, guard, chat UI | DONE | `agent-core/src/commonMain/kotlin/com/agent/code/opencode/OpenCodeManager.kt` · `agent-core/src/commonMain/kotlin/com/agent/code/opencode/AgentGuard.kt` · `provider-subsystem/src/commonMain/kotlin/com/agent/code/opencode/AgentBrain.kt` · `app-ui/src/commonMain/kotlin/com/agent/code/ui/AgentViewModel.kt` · `provider-subsystem/src/commonTest/kotlin/com/agent/code/opencode/RunSampleTest.kt` · `provider-subsystem/src/commonTest/kotlin/com/agent/code/opencode/RunAccumulatorTest.kt` · `provider-subsystem/src/commonTest/kotlin/com/agent/code/opencode/AgentBrainSessionTest.kt` · `provider-subsystem/src/commonTest/kotlin/com/agent/code/opencode/OpenCodeClientEventTest.kt` |
| M6.5 | Distribution: signed releases, in-app self-update | DONE | `.github/workflows/release.yml` · `androidApp/src/main/kotlin/com/agent/code/update/Updater.kt` · `androidApp/src/main/kotlin/com/agent/code/update/UpdateBanner.kt` — verified on device up to r42 |
| M7 | Durability: conversations (SQLite), journal, kanban | PARTIAL — conversations on SQLite (r44) | `agent-core/src/commonMain/kotlin/com/agent/code/core/conversation/SqlConversationStore.kt` · `agent-core/src/androidMain/kotlin/com/agent/code/core/conversation/AndroidSqlDb.kt` · `agent-core/src/androidHostTest/kotlin/com/agent/code/core/conversation/SqlConversationStoreTest.kt` — hand-written schema behind a 3-method `SqlDb` seam (SQLDelight dropped: codegen plugin risk under AGP 9.4's KMP-library plugin, no benefit for two tables). Kanban is still in-memory, journal still a WAL file. |
| M10 | Run integrity: guard gate, HITL receipts, run leases | NOT STARTED | design in TDD §18; M10.1 re-aimed at the opencode plugin |

M6 phases 3 (native tool expansion via `McpHost`) and 6 (multi-agent
orchestration) exist in code but are **not on the live path**; the app drives
`opencode run` and lets opencode own tool execution. Treat them as design
scaffolding until something calls them.

## Next, in order

| Order | Item | Size | Why this order |
|---|---|---|---|
| 1 | **M7 remainder** — journal + kanban on the same database | M | Nothing on the live path consumes kanban yet (see the M6 note), so migrating it now would be untested scaffolding; the journal is already durable as a WAL file. |
| 2 | **Tests in the owning modules** (`agent-core`, `workspace-engine`, `androidApp`) | M | CI passes those tasks vacuously today. Every claim above marked "tested from app-ui" moves home. |
| 3 | **M10.2** approval receipts + resume | M | Needs the M7 store; makes `BlockedOnApproval` resolvable instead of a dead end. |
| 4 | **M10.1** central guard gate, re-aimed | S–M | The plugin already denies writes outside the workspace; move the policy into one tested place instead of a JS string. |
| 5 | Loopback auth for the `serve`/SSE path | S | Only before that path is re-enabled; it is dead code today. |

## Known gaps (evidence, not opinions)

1. **Two of five modules have no test source set** — `workspace-engine`,
   `androidApp`. `.github/workflows/build.yml` lists their
   `testAndroidHostTest` tasks, which execute zero tests and pass. (`agent-core`
   is covered since r43.)
2. **Conversation durability is one conversation deep.** Restore picks the
   newest `Conversation` row
   (`agent-core/src/commonMain/kotlin/com/agent/code/core/conversation/SqlConversationStore.kt`);
   New Chat deletes every row (there is no history picker in the UI yet) and
   messages are capped at 2,000 per conversation. Kanban is in-memory; the
   journal is a WAL file, not a table. The r43 JSON-lines store
   (`agent-core/src/commonMain/kotlin/com/agent/code/core/conversation/ConversationStore.kt`)
   remains only as the one-time import source.
3. **Local inference is absent.** `MOBILE-TRAINING-RESEARCH.md` is research, not
   a plan item. The app requires a network model provider.
4. **The `serve`/SSE path has no auth** and is off the live path (see the note in
   `provider-subsystem/src/commonMain/kotlin/com/agent/code/opencode/OpenCodeClient.kt`).
   Any on-device app could drive it. Do not re-enable without a per-launch token.
5. **Guard ceiling:** `agent-core/src/commonMain/kotlin/com/agent/code/opencode/AgentGuard.kt`
   confines write *targets* (including bash redirection) but not
   interpreter-internal writes. Documented in the file.
6. **No F-Droid path.** The runtime downloads an executable, which F-Droid
   forbids. Self-update therefore requires granting "install unknown apps" once.
