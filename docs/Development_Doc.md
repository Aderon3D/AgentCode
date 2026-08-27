# MASTER TECHNICAL DESIGN DOCUMENT (TDD V14.1)
## Mission Control: Multi-Agent Autonomous Programming & Live Studio Platform
**Target Platforms:** Android 12+ (Primary — Direct APK / Sideload), Windows 11 (Secondary)  
**Core Framework:** Kotlin Multiplatform (KMP), Compose Multiplatform (CMP), Native C/C++ (NDK/CInterop), JNI  
**Distribution Model:** Direct APK Release (F-Droid / GitHub / Sideload) — No Play Store Constraints, Zero Full-Root Required  
**Document Status:** Complete Monolithic Master Reference (100% Comprehensive, Singular Source of Truth)

---

## 0. M0.5 — Self-Contained Bootstrap Spine (Testable)

**Purpose.** Before any native, NDK, SQLDelight, Shizuku, or multi-module work begins, land a **pure-Kotlin, dependency-light vertical slice** that proves the architecture spine end-to-end and is **fully unit-testable on the JVM host test target** that already exists in the skeleton (`./gradlew :shared:testAndroidHostTest`). M0.5 is the "does the skeleton think" milestone: it wires the FSM, the event-sourced WAL, the MCP tool-dispatch loop, the streaming JSON parser, the cost router, the Kanban model, and the conflated telemetry engine into one scripted, deterministic run with **zero Android-specific or native code in `commonMain`**.

**Hard constraints (what M0.5 is NOT).**
- No NDK / `libgit2` CInterop, no `tree-sitter`, no SQLDelight, no Shizuku, no DCEVM/dex-hotswap, no real network calls.
- No new Gradle subprojects: M0.5 lives as packages inside the existing module structure (`commonMain` + `androidMain` + `commonTest`). Promotion to the real `agent-core`/`provider-subsystem`/… modules happens in M1.
- Every storage/IO boundary is an **interface** with an in-memory fake in `commonTest`, so tests never touch the filesystem, the network, or Android APIs.

**Module packages (all under `com.agent.code`).**
| Package | Contents | Reuses doc types |
|---|---|---|
| `core` | `VirtualPath`, `EnergyAwareDispatchers`, `AutonomyPolicy`, `AgentState` FSM, `AgentEvent` + `WAL` (in-memory store), `AgentOrchestrator.stepOnce` | §2.4, §2.2, §7.1, §7.2, §7.3 |
| `provider` | `LlmProvider` + `LlmEvent`, `ResilientSseClient` (Flow-based stub), `HierarchicalModelRouter`, `StreamingJsonStateMachine` | §5.2, §8, §8.1 |
| `mcp` | `RiskLevel`, `ToolResult`, `AgentTool`, `FileSystemProvider`, `ProcessRunner`, `McpHost` dispatch loop, scripted `ReadFileTool`/`ApplyPatchTool` | §5.1 |
| `workspace` | `InMemoryFileSystem` (implements `FileSystemProvider`), `StubProcessRunner` (implements `ProcessRunner`) | §2.5 |
| `kanban` | `KanbanBoard`, `TaskCard`, column transitions (`BACKLOG→PLANNING→IN_PROGRESS→VERIFICATION→HUMAN_REVIEW→DONE`) | §9 |
| `telemetry` | `TelemetryEngine` with 50ms conflated batching | §10 |
| `bootstrap` | `MissionControlBootstrap.runDemo(): Timeline` — wires the above with a scripted `LlmProvider` | §7.3 |

**Interfaces introduced in M0.5 (replacing later heavy deps).**
```kotlin
// workspace/FileSystemProvider.kt  (fake in commonTest; real impl in M1)
interface FileSystemProvider {
    fun read(path: VirtualPath): String
    fun write(path: VirtualPath, content: String)
    fun exists(path: VirtualPath): Boolean
}

// workspace/ProcessRunner.kt  (shells to `git` in M1; no-op stub here)
interface ProcessRunner {
    suspend fun run(command: List<String>): String
}

// core/journal/WalStore.kt  (SQLDelight MissionControlDatabase replaced by in-memory)
interface WalStore {
    fun append(serialized: String)
    fun replay(): List<String>
}
```

**Deterministic demo contract.** `MissionControlBootstrap.runDemo()` drives one task through `Planning → ExecutingTool(read_file) → ExecutingTool(apply_diff_patch) → Verifying → Success`, feeding scripted `LlmEvent`s from a fake provider and emitting telemetry. It returns a `Timeline` (ordered list of `AgentEvent` + telemetry lines) so a test can assert:
1. WAL replay reconstructs the exact same state after a simulated crash (`WalStore` reset + `recoverState`).
2. `HierarchicalModelRouter.selectModel(LOW_LINT_FORMAT)` returns the cheapest registered provider.
3. `StreamingJsonStateMachine` yields a complete object after chunked delivery of a split JSON string.
4. `TelemetryEngine` batches >1 emit within 50ms into a single frame.

**Required dependencies (added to `gradle/libs.versions.toml` + per-module `build.gradle.kts`).** `kotlinx-coroutines-core`, `kotlinx-coroutines-test`, `kotlinx-serialization-json`, `kotlinx-datetime`. All common-Main-safe and JVM-testable.

**Exit criteria.** `./gradlew :app-ui:testAndroidHostTest` is green; `MissionControlBootstrapTest` asserts the four guarantees above. No Android manifest, NDK, or network changes required.

---

## 0.1 Milestone Status Tracker (as of 2026-08-26 — updated after merging PRs #41 M3, #42 M4, #43 M5)

Live status vs the §14 roadmap. Single source of truth for "what's done".

| Milestone | Scope | Status | Notes |
|---|---|---|---|
| **M0.5** Bootstrap spine | FSM + in-mem WAL + MCP loop + SSE JSON parser + Cost Router + Kanban + 50ms Telemetry | ✅ COMPLETE | `:app-ui:testAndroidHostTest` green; 4 M0.5 guarantees asserted in `MissionControlBootstrapTest`/`HierarchicalModelRouterTest`/`StreamingJsonStateMachineTest`/`TelemetryEngineTest`. |
| **M1** Headless Core | libgit2 NDK, Shizuku elevation, WAL journal, EnergyAwareDispatchers, MCP server, SSE client | ✅ COMPLETE | libgit2 JNI (`LibGit2Backend`), `FileBackedWalStore`, `EnergyAwareDispatchers`, `McpHost`, `ResilientSseClient`, `ShizukuFsProvider` (privileged read/write/exists via `Shizuku.newProcess` reflection; falls back to `RealFileSystem`) all done. |
| **M2** Multi-Agent Concurrency | Sparse worktrees, auto-squash, lock coordinator, 4-tier semantic funnel, tree-sitter | ✅ COMPLETE | `WorktreeManager`, `TaskLockCoordinator`, `WorkspaceLockManager`, `SemanticConflictFunnel`, `TreeSitterBackend` (JNI) landed; concurrency + tree-sitter tests green. |
| **M3** UI & Cost Routing | CMP Shell + 50ms conflated telemetry stream + streaming JSON SM + adaptive power governor | ✅ COMPLETE | CMP dashboard + telemetry + streaming-JSON + cost-routing panels landed (DashboardScreen / MissionControlPanel / CostRoutingPanel / StreamingJsonPanel, TelemetryEngine, App.kt wiring). Live Canvas deferred per §1.2. |
| **M4** Android Live Testing | Resilient FGS, Geometric Layout Oracle, dual-mode Accessibility Engine | ✅ COMPLETE | `ResilientAgentForegroundService` (FGS: wake/Wi-Fi locks + watchdog alarm + START_STICKY) + `GeometricLayoutOracle` + `AccessibilityEngine` (impl + `StubAccessibilityEngine`) + `UiTools` + `FileWatcherJvm`, all w/ tests. |
| **M5** Security & Hardening | Non-interactive Git auth, visual 3-way merge, SecureVault (KeyStore) | 🟡 PARTIAL | `GitAuthWrapper` + `SecureVault` (KeyStore) + `RuntimeDiagnosticsTool` landed w/ tests. **Visual 3-Way Merge screen + `FetchDocTool` NOT implemented.** |

### M5.x — Scheduled Upgrades (features cut during M0.5–M5 implementation, reintegrated as phased work)

Features originally specified in the TDD that were simplified or deferred during actual implementation. Each upgrade is an additive, backward-compatible enhancement — no breaking changes to existing interfaces.

| Phase | Scope | Depends On | Features |
|---|---|---|---|
| **M5.1** Interface Enrichment | Core API surface | M5 complete | `FileSystemProvider` → suspend + `applyPatch`/`walkTree`; `ProcessRunner` → `ProcessConfiguration` + streaming; `AgentEvent.TokenChunkReceived`; Kanban `HUMAN_REVIEW` column doc |
| **M5.2** Platform Services | Android-native | M5.1 | `AdaptivePowerGovernor` full thermal+battery impl replacing stub; `PrivilegedElevationManager` Shizuku elevation |
| **M5.3** Tool & Protocol Layer | Agent capabilities | M5.1 | `MissionControlMcpServer` full tool registry; `FetchDocTool`; `CircuitBreaker` budget tracking (`TaskSafetyBudget`) |
| **M5.4** Doc Hygiene | Documentation | None (parallel) | Fix phantom paths (`platform-android`, `shared/`, `androidApp/src/androidMain/`); README 4-module update; dependency DAG correction |

**Structural gap — RESOLVED (2026-08-24):** The `:shared` package monolith has been promoted to real Gradle modules. Realized: `agent-core`, `provider-subsystem`, `workspace-engine`, `app-ui`, plus the existing `androidApp` (5 `build.gradle.kts`, 0 `:shared`). `:app-ui:testAndroidHostTest` and `:androidApp:assembleDebug` are both green. Deferred (per §14 / not yet needed): `data-layer`, `live-canvas`, `desktopApp` — and `buildSrc` convention plugins were deliberately skipped in favor of standalone per-module build files (see §1.2 note).

## 1. System Architecture & Component Topology

### 1.1 The Mission Control Paradigm
Mission Control is a multi-agent, autonomous programming platform designed for mobile and desktop. Operating under a **Supervisor/Mission Control Paradigm**, the developer manages high-level features on an AI-Native Kanban board, inspects live telemetry, and interacts with an **Embedded Live Canvas**, while autonomous AI agents independently plan, edit code, hot-reload, test native UIs, and resolve merge conflicts in parallel across isolated Git Worktrees.

```text
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        MISSION CONTROL ARCHITECTURE (V14.0)                            │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ PRESENTATION (CMP) │ Mission Control Dashboard + Embedded In-Process Live Canvas       │
├────────────────────┼───────────────────────────────────────────────────────────────────┤
│ STREAMING UX       │ Streaming Partial-JSON AST Parser + 50ms Conflated Telemetry      │
├────────────────────┼───────────────────────────────────────────────────────────────────┤
│ MCP ECOSYSTEM      │ Model Context Protocol (MCP) Host + Standardized JSON-RPC Server  │
├────────────────────┼───────────────────────────────────────────────────────────────────┤
│ AGENT ORCHESTRATOR │ Event-Sourced WAL + Autonomy Policy + Token Pruner + BPE Engine   │
├────────────────────┼───────────────────────────────────────────────────────────────────┤
│ COST & ROUTING     │ Hierarchical Model Router (Tier 1/2) + Resilient SSE Client       │
├────────────────────┼───────────────────────────────────────────────────────────────────┤
│ CONCURRENCY ENGINE │ Git Sparse Worktrees (libgit2 NDK) + AST Symbol Lock Registry     │
├────────────────────┼───────────────────────────────────────────────────────────────────┤
│ SEMANTIC ORACLE    │ 4-Tier Conflict Funnel (AST Slicing -> Targeted Test Execution)   │
├────────────────────┼───────────────────────────────────────────────────────────────────┤
│ UI TESTING ORACLE  │ Multi-DPI Geometric Bounding Box Engine + Dual-Mode Accessibility │
├────────────────────┼───────────────────────────────────────────────────────────────────┤
│ LIVE RUNTIME       │ Desktop DCEVM + Android Method-Body Dex Swapping / Declarative DSL│
├────────────────────┼───────────────────────────────────────────────────────────────────┤
│ OS / HARDWARE      │ libgit2 NDK + Shizuku Elevation + Thermal Governor + 5-Layer FGS │
└────────────────────┴───────────────────────────────────────────────────────────────────┘
```

### 1.2 Module Topology

> **Realized (2026-08-24) vs. original doc plan.** The doc's 7-module layout (`buildSrc` + `agent-core`/`data-layer`/`provider-subsystem`/`workspace-engine`/`live-canvas`/`app-ui`/`desktopApp`) was a forward-looking target. The actual promotion delivered a **cycle-free 4-module split** that differs from the doc in three ways, all intentional:
> 1. **`buildSrc` skipped** — standalone per-module `build.gradle.kts` instead of a convention-plugin. Lower risk in the constrained build env; revisit when a 5th+ module appears.
> 2. **`agent-core` absorbs `mcp/**`, `core/lock/**` (incl. `SemanticConflictFunnel`), the `workspace` interface DEFs (`FileSystemProvider`, `ProcessRunner`), and `workspace.KotlinParser`.** The doc put MCP/Tools under `provider-subsystem`/`workspace-engine`, but `AgentOrchestrator` (core) calls `McpHost` and `SemanticConflictFunnel` (which needs `KotlinParser` + `ProcessRunner`). Routing those through other modules created an `agent-core → workspace-engine → agent-core` cycle, so they were folded into `agent-core`.
> 3. **`data-layer` / `live-canvas` / `desktopApp` deferred** — no SQLDelight/Tree-Sitter-CInterop/desktop work exists yet.

```text
root/
├── agent-core/          # [commonMain] core/** (FSM, journal, policy, power, tools, lock**) + mcp/** + workspace interface DEFs (FileSystemProvider, ProcessRunner) + workspace.KotlinParser
│                       #                **SemanticConflictFunnel lives here (not workspace-engine) to break the cycle
│                       # [androidMain] core/power/AndroidPowerGovernor, core/journal/FileBackedWalStore
├── provider-subsystem/  # [commonMain] provider/** — LlmProvider, ResilientSseClient (coroutines-only, no ktor), HierarchicalModelRouter, StreamingJsonStateMachine, ProviderRegistry
├── workspace-engine/    # [commonMain] workspace/** (impls + InMemoryFileSystem/StubProcessRunner) + core/lock/FileWatcher.kt (expect)
│                       # [androidMain] RealFileSystem, GitProcessRunner, LibGit2Backend, ShizukuFsProvider, TreeSitterBackend + FileWatcherJvm.kt (actual)
├── app-ui/              # [commonMain CMP] kanban/**, bootstrap/**, App.kt  |  [commonTest + androidHostTest] all M0.5/M1/M2 tests
└── androidApp/          # [androidMain] ui/* probes (ShizukuStatusDemo, ProbeDashboard, LibGit2Demo, …), MainActivity  |  consumes app-ui + agent-core + provider-subsystem + workspace-engine
```

**Dependency DAG (no cycles):** `agent-core` (leaf) ← `provider-subsystem` / `workspace-engine` ← `app-ui` ← `androidApp`. `agent-core` has zero inter-module deps.

---

## 2. Hardware Physics, Low-Level OS & Storage Layer

### 2.1 Zero-Root Privileged Elevation (Shizuku & Settings)
On Android 12+, stock Android's Phantom Process Killer terminates child processes if the app exceeds 32 subprocesses or uses high background CPU. Under the direct APK release model, the platform uses **Shizuku** (or 1-tap Wireless Debugging pairing via standard Developer Options) to neutralize this constraint without rooting the device.

**Current implementation (M1):** `ShizukuFsProvider` handles privileged file I/O via Shizuku reflection. No dedicated elevation manager yet.

**M5.2 upgrade — add `PrivilegedElevationManager`:**
```kotlin
// Planned: agent-core/src/androidMain/kotlin/com/agent/code/core/elevation/PrivilegedElevationManager.kt
class PrivilegedElevationManager(private val context: Context) {

    fun applyZeroRootOptimizations(): Result<ElevationStatus> {
        if (!Shizuku.pingBinder()) {
            return Result.success(ElevationStatus.StandardUserSpace)
        }

        return try {
            executeAdbCommand("device_config put activity_manager max_phantom_processes 2147483647")
            executeAdbCommand("settings put global settings_enable_monitor_phantom_procs false")
            Result.success(ElevationStatus.PrivilegedAdbUncapped)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun requestDirectBatteryOptimizationExemption() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    private fun executeAdbCommand(command: String) {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        process.waitFor()
    }
}

enum class ElevationStatus { StandardUserSpace, PrivilegedAdbUncapped }
```

> **Migration note:** Called once at agent startup in `ResilientAgentForegroundService.onStartCommand` or `MainActivity.onCreate`. Non-blocking — returns `StandardUserSpace` if Shizuku unavailable. `ShizukuFsProvider` remains the I/O layer; this manager only tunes OS-level constraints.

### 2.2 Heterogeneous CPU Topology Scheduling (big.LITTLE Architecture)
```kotlin
// agent-core/src/commonMain/kotlin/com/agent/code/core/concurrency/EnergyAwareDispatchers.kt
object EnergyAwareDispatchers {
    val EfficiencyIO: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)

    val ComputeBurst: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(
        (availableProcessors() - 2).coerceAtLeast(2)
    )
}

internal expect fun availableProcessors(): Int
```

### 2.3 Adaptive Thermal & Power Governor

**Current implementation (M3):** Stub only — `StubPowerGovernor` returns `BALANCED_BATTERY` always.

```kotlin
// agent-core/src/commonMain/kotlin/com/agent/code/core/power/PowerGovernor.kt
interface PowerGovernor {
    val currentProfile: StateFlow<OperatingProfile>
}

// agent-core/src/commonMain/kotlin/com/agent/code/core/power/StubPowerGovernor.kt
class StubPowerGovernor : PowerGovernor {
    override val currentProfile: StateFlow<OperatingProfile> = MutableStateFlow(OperatingProfile.BALANCED_BATTERY)
}
```

**M5.2 upgrade — full thermal+battery implementation:**
```kotlin
// Planned: agent-core/src/androidMain/kotlin/com/agent/code/core/power/AndroidPowerGovernor.kt
enum class OperatingProfile {
    TURBO_PLUGGED,       // Charging & Cool: Max concurrency (4 parallel agents), full burst
    BALANCED_BATTERY,    // Battery >20% & Normal Temp: 1-2 concurrent agents, 50ms cooling delay
    ECO_PRESERVATION     // Battery <20% OR Thermal SEVERE: Suspend local builds, serialize steps
}

class AndroidPowerGovernor(
    private val context: Context,
    private val powerManager: PowerManager
) : PowerGovernor {
    private val _currentProfile = MutableStateFlow(OperatingProfile.BALANCED_BATTERY)
    override val currentProfile: StateFlow<OperatingProfile> = _currentProfile.asStateFlow()

    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var isPluggedIn = false
    private var batteryLevelPercent = 100

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.addThermalStatusListener { status ->
                thermalStatus = status
                evaluateProfile()
            }
        }
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent?.let {
                    isPluggedIn = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let { s ->
                        s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
                    }
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    batteryLevelPercent = (level * 100) / scale
                    evaluateProfile()
                }
            }
        }, batteryFilter)
    }

    private fun evaluateProfile() {
        _currentProfile.value = when {
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE || batteryLevelPercent < 20 ->
                OperatingProfile.ECO_PRESERVATION
            isPluggedIn && thermalStatus <= PowerManager.THERMAL_STATUS_MODERATE ->
                OperatingProfile.TURBO_PLUGGED
            else -> OperatingProfile.BALANCED_BATTERY
        }
    }
}
```

> **Migration note:** `StubPowerGovernor` stays as the JVM/host test double. `AgentOrchestrator` already accepts `PowerGovernor` interface — swap `StubPowerGovernor()` for `AndroidPowerGovernor(context, powerManager)` in `androidApp` DI wiring. No orchestrator changes needed.

### 2.4 Virtual Path Abstraction
```kotlin
// agent-core/src/commonMain/kotlin/com/agent/code/core/path/VirtualPath.kt
@JvmInline
@kotlinx.serialization.Serializable
value class VirtualPath private constructor(val rawPath: String) {
    val isAbsolute: Boolean get() = rawPath.startsWith("/") || WINDOWS_DRIVE_REGEX.matches(rawPath)
    val fileName: String get() = rawPath.substringAfterLast('/').substringAfterLast('\\')
    val extension: String get() = fileName.substringAfterLast('.', "")

    fun resolve(child: String): VirtualPath {
        val sanitizedChild = child.replace('\\', '/')
        val cleanBase = rawPath.trimEnd('/', '\\')
        return VirtualPath("$cleanBase/$sanitizedChild")
    }

    fun parent(): VirtualPath? {
        if (rawPath == "/") return null
        val lastSlash = maxOf(rawPath.lastIndexOf('/'), rawPath.lastIndexOf('\\'))
        if (lastSlash < 0) return null
        if (lastSlash == 0) return VirtualPath("/")
        val base = rawPath.substring(0, lastSlash)
        if (base.matches(Regex("^[a-zA-Z]:$"))) return VirtualPath("$base/")
        return VirtualPath(base)
    }

    companion object {
        private val WINDOWS_DRIVE_REGEX = Regex("^[a-zA-Z]:[/\\\\].*")
        fun of(path: String): VirtualPath = VirtualPath(path.replace('\\', '/'))
    }
}
```

### 2.5 Native POSIX File System Provider

**Current implementation (M1):**
```kotlin
// agent-core/src/commonMain/kotlin/com/agent/code/workspace/FileSystemProvider.kt
sealed class FileError(
    override val message: String,
    open val path: VirtualPath? = null,
    cause: Throwable? = null
) : Exception(message, cause) {
    class NotFound(path: VirtualPath, message: String) : FileError(message, path)
    class PermissionDenied(path: VirtualPath, message: String) : FileError(message, path)
    class PatchFailed(path: VirtualPath, message: String) : FileError(message, path)
    class IOError(path: VirtualPath?, message: String, cause: Throwable? = null) : FileError(message, path, cause)
}

interface FileSystemProvider {
    fun read(path: VirtualPath): Result<String>
    fun write(path: VirtualPath, content: String): Result<Unit>
    fun exists(path: VirtualPath): Boolean
    fun delete(path: VirtualPath): Result<Unit>
}
```

**M5.1 upgrade — add `suspend`, `applyPatch`, `walkTree`:**
```kotlin
// Planned: agent-core/src/commonMain/kotlin/com/agent/code/workspace/FileSystemProvider.kt
data class PatchOperation(val searchBlock: String, val replaceBlock: String)

sealed interface FileNode {
    val path: VirtualPath
    val name: String
    data class File(override val path: VirtualPath, override val name: String, val sizeBytes: Long, val lastModifiedMs: Long) : FileNode
    data class Directory(override val path: VirtualPath, override val name: String, val children: List<FileNode>) : FileNode
}

interface FileSystemProvider {
    suspend fun read(path: VirtualPath): Result<String>
    suspend fun write(path: VirtualPath, content: String): Result<Unit>
    suspend fun exists(path: VirtualPath): Boolean
    suspend fun delete(path: VirtualPath): Result<Unit>
    suspend fun applyPatch(path: VirtualPath, patches: List<PatchOperation>): Result<Unit>
    suspend fun walkTree(root: VirtualPath, maxDepth: Int = 10, ignorePatterns: List<String> = listOf(".git", "build", "node_modules", ".gradle")): Result<FileNode.Directory>
}
```

> **Migration note:** Current callers use synchronous `read`/`write`/`exists`/`delete`. M5.1 wraps them in `suspend`. `InMemoryFileSystem` and `RealFileSystem` gain default `applyPatch` (search-and-replace) and `walkTree` (recursive directory listing) implementations. `ShizukuFsProvider` delegates `applyPatch`/`walkTree` to `fallback` (sandboxed paths) or shells out via Shizuku (escalated paths).

### 2.6 Bare-Metal `libgit2` NDK Engine

**Current implementation (M1):** `GitBackend` interface with `LibGit2Backend` (JNI) and `CliGitBackend` (host) implementations.
```kotlin
// workspace-engine/src/commonMain/kotlin/com/agent/code/workspace/GitBackend.kt
interface GitBackend {
    suspend fun initRepo(path: VirtualPath): Result<Unit>
    suspend fun worktreeAdd(repo: VirtualPath, name: String, path: VirtualPath, baseBranch: String): Result<Unit>
    suspend fun worktreeRemove(repo: VirtualPath, name: String): Result<Unit>
    suspend fun checkout(repo: VirtualPath, branch: String): Result<Unit>
    suspend fun mergeSquash(repo: VirtualPath, branch: String): Result<Unit>
    suspend fun addAll(repo: VirtualPath): Result<Unit>
    suspend fun commit(repo: VirtualPath, message: String): Result<Unit>
    suspend fun branchDelete(repo: VirtualPath, name: String): Result<Unit>
    suspend fun branchRename(repo: VirtualPath, oldName: String, newName: String): Result<Unit>
    suspend fun sparseCheckoutSet(repo: VirtualPath, directories: List<String>): Result<Unit>
}

// workspace-engine/src/androidMain/kotlin/com/agent/code/workspace/LibGit2Backend.kt
class LibGit2Backend : GitBackend {
    companion object { init { System.loadLibrary("git2jni") } }
    private external fun nativeInit(): String?
    private external fun nativeWorktreeAdd(repo: String, name: String, path: String, base: String): String?
    // ... JNI methods for each GitBackend operation
}
```

> **Doc note:** The original TDD showed `LibGit2Engine` as a standalone `expect class`. The actual implementation uses `GitBackend` interface with `LibGit2Backend` (Android/JNI) and `CliGitBackend` (JVM host) implementations. This is cleaner — `WorktreeManager` depends on the interface, not the implementation.

### 2.7 Fallback Process Runner & Startup Self-Healer

**Current implementation (M1):**
```kotlin
// agent-core/src/commonMain/kotlin/com/agent/code/workspace/ProcessRunner.kt
interface ProcessRunner {
    suspend fun run(command: List<String>): Result<String>
}
```

**M5.1 upgrade — add `ProcessConfiguration`, streaming, `ProcessEvent`:**
```kotlin
// Planned: agent-core/src/commonMain/kotlin/com/agent/code/workspace/ProcessRunner.kt
data class ProcessConfiguration(
    val command: String,
    val args: List<String> = emptyList(),
    val workingDir: VirtualPath,
    val environmentVariables: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 120_000
)

data class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String, val executionTimeMs: Long)

sealed interface ProcessEvent {
    data class StdoutLine(val line: String) : ProcessEvent
    data class StderrLine(val line: String) : ProcessEvent
    data class Terminated(val exitCode: Int) : ProcessEvent
}

interface ProcessRunner {
    suspend fun run(command: List<String>): Result<String>  // retained for M0.5 callers
    suspend fun execute(config: ProcessConfiguration): Result<ProcessOutput>
    fun executeStreaming(config: ProcessConfiguration): Flow<ProcessEvent>
}
```

> **Migration note:** Existing `run(command)` stays as a convenience shorthand (delegates to `execute` with default config). `GitProcessRunner` and `StubProcessRunner` gain `execute`/`executeStreaming` overrides. `RuntimeDiagnosticsTool` migrates to `execute` for exit-code inspection.

---

## 3. Concurrency, Sparse Worktrees & Git Bloat Management

### 3.1 Worktree Manager with Auto-Squash & GC
```kotlin
// workspace-engine/src/commonMain/kotlin/com/agent/code/workspace/WorktreeManager.kt
class WorktreeManager(
    private val rootRepoPath: VirtualPath,
    private val gitBackend: GitBackend
) {
    suspend fun createSparseWorktree(
        taskId: String,
        targetDirectories: List<String> = emptyList(),
        baseBranch: String = "main"
    ): Result<VirtualPath> {
        val worktreePath = rootRepoPath.resolve(".worktrees/task-$taskId")
        val branch = "agent/task-$taskId"

        gitBackend.worktreeAdd(rootRepoPath, branch, worktreePath, baseBranch)
            .onFailure { return Result.failure(it) }

        if (targetDirectories.isNotEmpty()) {
            gitBackend.sparseCheckoutSet(worktreePath, targetDirectories).onFailure { originalError ->
                gitBackend.worktreeRemove(rootRepoPath, ".worktrees/task-$taskId")
                gitBackend.branchDelete(rootRepoPath, branch)
                return Result.failure(originalError)
            }
        }
        return Result.success(worktreePath)
    }

    suspend fun finalizeAndSquashBranch(taskId: String, targetBranch: String = "main"): Result<Unit> {
        val branch = "agent/task-$taskId"
        gitBackend.checkout(rootRepoPath, targetBranch).onFailure { return Result.failure(it) }
        gitBackend.mergeSquash(rootRepoPath, branch).onFailure { return Result.failure(it) }
        gitBackend.addAll(rootRepoPath).onFailure { return Result.failure(it) }
        gitBackend.commit(rootRepoPath, "squash task $taskId").onFailure { return Result.failure(it) }
        gitBackend.worktreeRemove(rootRepoPath, ".worktrees/task-$taskId").onFailure { return Result.failure(it) }
        gitBackend.branchDelete(rootRepoPath, branch).onFailure { return Result.failure(it) }
        return Result.success(Unit)
    }
}
```

### 3.2 Lock Manager & Task Lock Coordinator
```kotlin
// agent-core/src/commonMain/kotlin/com/agent/code/core/lock/WorkspaceLockManager.kt
sealed interface ConflictRisk {
    object None : ConflictRisk
    data class FileOverlapRequiresMerge(val files: Set<VirtualPath>) : ConflictRisk
    data class FatalSymbolCollision(val symbols: Set<String>) : ConflictRisk
}

data class ActiveTaskLock(
    val agentTaskId: String,
    val branchName: String,
    val lockedFiles: Set<VirtualPath>,
    val lockedSymbolUuids: Set<String>
)

class WorkspaceLockManager {
    private val stateMutex = Mutex()
    private val activeLocks = mutableMapOf<String, ActiveTaskLock>()
    private var maintenanceDeferred: CompletableDeferred<Unit>? = null

    suspend fun waitForMaintenanceAndRegisterLock(taskId: String, lock: ActiveTaskLock): ConflictRisk {
        while (true) {
            val deferred = stateMutex.withLock {
                if (maintenanceDeferred == null) {
                    val risk = evaluateCollisionRisk(lock.lockedFiles, lock.lockedSymbolUuids)
                    if (risk is ConflictRisk.None || risk is ConflictRisk.FileOverlapRequiresMerge) {
                        activeLocks[taskId] = lock
                    }
                    return risk
                }
                maintenanceDeferred!!
            }
            deferred.await()
        }
    }

    fun releaseLock(taskId: String) {
        activeLocks.remove(taskId)
    }

    suspend fun tryAcquireMaintenanceLock(): Boolean = stateMutex.withLock {
        if (activeLocks.isNotEmpty() || maintenanceDeferred != null) return false
        maintenanceDeferred = CompletableDeferred()
        return true
    }

    suspend fun releaseMaintenanceLock() = stateMutex.withLock {
        maintenanceDeferred?.complete(Unit)
        maintenanceDeferred = null
    }

    private fun evaluateCollisionRisk(proposedFiles: Set<VirtualPath>, proposedSymbols: Set<String>): ConflictRisk {
        val overlappingSymbols = activeLocks.values.flatMap { it.lockedSymbolUuids }.intersect(proposedSymbols)
        if (overlappingSymbols.isNotEmpty()) return ConflictRisk.FatalSymbolCollision(overlappingSymbols)
        
        val overlappingFiles = activeLocks.values.flatMap { it.lockedFiles }.intersect(proposedFiles)
        if (overlappingFiles.isNotEmpty()) return ConflictRisk.FileOverlapRequiresMerge(overlappingFiles)
        
        return ConflictRisk.None
    }
}

// agent-core/src/commonMain/kotlin/com/agent/code/core/lock/TaskLockCoordinator.kt
class TaskLockCoordinator(
    private val lockManager: WorkspaceLockManager
) {
    private val symbolWaiters = mutableMapOf<String, MutableList<CompletableDeferred<Unit>>>()
    private val coordinatorMutex = Mutex()

    suspend fun acquireTaskExecutionPermit(
        taskId: String,
        branchName: String,
        files: Set<VirtualPath>,
        symbols: Set<String>
    ): ExecutionPermit {
        val requestedLock = ActiveTaskLock(taskId, branchName, files, symbols)

        while (true) {
            val registrationResult = lockManager.waitForMaintenanceAndRegisterLock(taskId, requestedLock)

            when (registrationResult) {
                is ConflictRisk.None -> {
                    return ExecutionPermit(taskId = taskId, requiresAst3WayMerge = false)
                }
                is ConflictRisk.FileOverlapRequiresMerge -> {
                    return ExecutionPermit(taskId = taskId, requiresAst3WayMerge = true, overlappingFiles = registrationResult.files)
                }
                is ConflictRisk.FatalSymbolCollision -> {
                    val waitDeferred = CompletableDeferred<Unit>()
                    coordinatorMutex.withLock {
                        for (symbol in registrationResult.symbols) {
                            symbolWaiters.getOrPut(symbol) { mutableListOf() }.add(waitDeferred)
                        }
                    }
                    waitDeferred.await()
                }
            }
        }
    }

    suspend fun releaseTaskExecutionPermit(taskId: String, symbols: Set<String>) {
        lockManager.releaseLock(taskId)
        coordinatorMutex.withLock {
            for (symbol in symbols) {
                val waiters = symbolWaiters.remove(symbol)
                waiters?.forEach { it.complete(Unit) }
            }
        }
    }
}

data class ExecutionPermit(
    val taskId: String,
    val requiresAst3WayMerge: Boolean,
    val overlappingFiles: Set<VirtualPath> = emptySet()
)

// workspace-engine/src/commonMain/kotlin/com/agent/code/core/lock/FileWatcher.kt
enum class ChangeType { CREATED, MODIFIED, DELETED }

expect class FileWatcher {
    fun startWatching(
        targetDirectory: VirtualPath,
        onFileChanged: (VirtualPath, ChangeType) -> Unit
    )
    fun stopWatching()
}
```

---

## 4. Semantic Anti-Regression & Conflict Funnel (4-Tier)

```kotlin
// agent-core/src/commonMain/kotlin/com/agent/code/core/lock/SemanticConflictFunnel.kt
class SemanticConflictFunnel(
    private val treeSitter: TreeSitterNativeBridge,
    private val lockManager: WorkspaceLockManager,
    private val testRunner: ProcessRunner
) {
    fun checkPreWriteCollision(taskId: String, requestedSymbols: Set<String>): ConflictRisk {
        return lockManager.evaluateCollisionRisk(emptySet(), requestedSymbols)
    }

    suspend fun verifyBranchIntegration(
        branchName: String,
        changedFiles: List<VirtualPath>
    ): SemanticVerificationResult {
        // Tier 2: Backward AST Slicing (Change-Impact Analysis)
        val impactedSymbols = treeSitter.computeImpactedSymbolSlice(changedFiles)
        
        // Tier 3: Bounded Mutation Testing on Impact Set
        val targetedTests = treeSitter.findTargetedTestsForSlice(impactedSymbols)
        val testResult = testRunner.execute(ProcessConfiguration("gradle", listOf("test", "--tests", targetedTests.joinToString(","))))
        
        if (!testResult.isSuccess) {
            return SemanticVerificationResult.Failed(testResult.getOrNull()?.stderr ?: "Targeted tests failed")
        }
        return SemanticVerificationResult.Passed
    }
}

sealed interface SemanticVerificationResult {
    object Passed : SemanticVerificationResult
    data class Failed(val reason: String) : SemanticVerificationResult
    data class EscalationRequired(val ambiguousInvariants: List<String>) : SemanticVerificationResult
}
```

---

## 5. Model Context Protocol (MCP) & Streaming JSON Parser

### 5.1 Native MCP Host Architecture & Agent Tool Contracts

**Current implementation (M0.5):** Minimal `McpHost` dispatcher with two built-in tools.
```kotlin
// agent-core/src/commonMain/kotlin/com/agent/code/mcp/McpHost.kt
interface AgentTool {
    val name: String
    val description: String
    val riskLevel: RiskLevel
    suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult
}

class McpHost(
    private val fileSystem: FileSystemProvider,
    private val processRunner: ProcessRunner
) {
    private val tools: Map<String, AgentTool> = mapOf(
        ReadFileTool(fileSystem).let { it.name to it },
        ApplyPatchTool(fileSystem).let { it.name to it }
    ).toMap()

    fun dispatch(toolCall: ToolCall): ToolResult {
        val tool = tools[toolCall.toolName]
            ?: return ToolResult(toolCall.id, false, "unknown tool: ${toolCall.toolName}", 0L)
        return kotlinx.coroutines.runBlocking { tool.execute(toolCall.argumentsJson, fileSystem, processRunner) }
    }

    fun listTools(): List<String> = tools.keys.toList()
}
```

**M5.3 upgrade — full MCP server with JSON-RPC protocol + extended tool registry:**
```kotlin
// Planned: agent-core/src/commonMain/kotlin/com/agent/code/mcp/McpContracts.kt
data class McpToolDefinition(val name: String, val description: String, val inputSchema: JsonObject)
data class McpToolCall(val name: String, val arguments: JsonObject)
data class McpContent(val type: String, val text: String? = null, val data: String? = null)
data class McpToolResult(val content: List<McpContent>, val isError: Boolean = false)

interface McpClient {
    suspend fun listTools(): List<McpToolDefinition>
    suspend fun callTool(call: McpCall): McpToolResult
}

// Planned: agent-core/src/commonMain/kotlin/com/agent/code/mcp/MissionControlMcpServer.kt
class MissionControlMcpServer(
    private val accessibilityEngine: AccessibilityEngine,
    private val fileSystem: FileSystemProvider
) {
    fun getSupportedTools(): List<McpToolDefinition> = listOf(
        McpToolDefinition("inspect_ui_state", "Dumps Android/Windows semantic layout tree", JsonObject(emptyMap())),
        McpToolDefinition("interact_ui_element", "Performs native click, text input, or swipe gestures", JsonObject(emptyMap())),
        McpToolDefinition("read_file", "Reads file contents safely", JsonObject(emptyMap())),
        McpToolDefinition("apply_diff_patch", "Applies search-and-replace atomic patch", JsonObject(emptyMap()))
    )
}
```

> **Migration note:** `McpHost` (current) stays for M0.5 backward compat. `MissionControlMcpServer` wraps it and adds UI tools (`inspect_ui_state`, `interact_ui_element`) via `AccessibilityEngine`. Wired in `androidApp` DI after M5.1 `FileSystemProvider` gains `suspend` (so tools can call async FS ops).

### 5.2 Streaming Partial-JSON AST Parser
```kotlin
// provider-subsystem/src/commonMain/kotlin/com/agent/code/provider/StreamingJsonStateMachine.kt
data class ToolArgumentDelta(val toolName: String, val partialPayload: String)

class StreamingJsonStateMachine {
    private var state = State.AWAITING_KEY
    private var containerDepth = 1
    private var inNestedString = false
    private var isEscaped = false

    fun processSkippingRawValue(c: Char) {
        when {
            c == '"' && !isEscaped -> {
                inNestedString = !inNestedString
                isEscaped = false
            }
            !inNestedString && (c == '{' || c == '[') -> {
                containerDepth++
                isEscaped = false
            }
            !inNestedString && (c == '}' || c == ']') -> {
                containerDepth--
                if (containerDepth == 1) state = State.AWAITING_KEY
                if (containerDepth == 0) state = State.FINISHED
                isEscaped = false
            }
            !inNestedString && containerDepth == 1 && (c == ',' || c == '\n') -> {
                state = State.AWAITING_KEY
                isEscaped = false
            }
            c == '\\' && !isEscaped -> isEscaped = true
            else -> isEscaped = false
        }
    }
}

enum class State { AWAITING_KEY, READING_KEY, READING_VALUE, SKIPPING_RAW_VALUE, FINISHED }
```

---

## 6. Dual-Engine Live Reload & UI Testing Oracle

### 6.1 Dual-Engine Strategy
*   **Desktop (Windows):** Powered by **JetBrains Runtime (JBR) + DCEVM** (`compose-hot-reload` / Firework) for unrestricted structural class redefinition (<300ms).
*   **Mobile (Android):** Powered by **HotSwan/Live Edit ART-TI Bytecode Method-Body Swapping** for real code + an in-memory **Declarative Dynamic Compose Sandbox** for sub-50ms visual layout tweaking.

### 6.2 Geometric Layout Oracle
```kotlin
// workspace-engine/src/commonMain/kotlin/com/agent/code/ui/GeometricLayoutOracle.kt
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun intersects(other: Rect): Boolean = left < other.right && right > other.left && top < other.bottom && bottom > other.top
    fun isOutOfBounds(container: Rect): Boolean = left < container.left || right > container.right || top < container.top || bottom > container.bottom
}

data class UiElementNode(val id: String?, val bounds: Rect, val parentBounds: Rect, val isDecorative: Boolean)

class GeometricLayoutOracle {
    fun verifyLayoutCorrectness(elements: List<UiElementNode>): List<LayoutBug> {
        val detectedBugs = mutableListOf<LayoutBug>()

        for (element in elements) {
            if (element.bounds.isOutOfBounds(element.parentBounds)) {
                detectedBugs.add(LayoutBug.Clipping(element.id ?: "anonymous", element.bounds, element.parentBounds))
            }
        }

        for (i in elements.indices) {
            for (j in i + 1 until elements.size) {
                val a = elements[i]
                val b = elements[j]
                if (!a.isDecorative && !b.isDecorative && a.bounds.intersects(b.bounds)) {
                    detectedBugs.add(LayoutBug.Overlap(a.id, b.id, a.bounds, b.bounds))
                }
            }
        }
        return detectedBugs
    }
}

sealed interface LayoutBug {
    data class Clipping(val elementId: String, val bounds: Rect, val parentBounds: Rect) : LayoutBug
    data class Overlap(val elementA: String?, val elementB: String?, val boundsA: Rect, val boundsB: Rect) : LayoutBug
}
```

### 6.3 Accessibility Engine & Agent UI Tools
```kotlin
// workspace-engine/src/commonMain/kotlin/com/agent/code/ui/AccessibilityEngine.kt
enum class UiActionType { CLICK, LONG_CLICK, TYPE_TEXT, SWIPE, CLEAR_TEXT }

data class UiElementSelector(
    val resourceId: String? = null,
    val textMatches: String? = null,
    val targetBoundsCenter: Pair<Int, Int>? = null
)

interface AccessibilityEngine {
    suspend fun dumpSemanticTreeXml(): String
    suspend fun captureScreenshot(): ByteArray?
    suspend fun performClick(selector: UiElementSelector): Result<Unit>
    suspend fun performInputText(selector: UiElementSelector, text: String): Result<Unit>
    suspend fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long): Result<Unit>
}

// workspace-engine/src/commonMain/kotlin/com/agent/code/ui/UiTools.kt
class InspectUiTool(private val accessibilityEngine: AccessibilityEngine) : AgentTool {
    override val name = "inspect_ui_state"
    override val description = "Returns a text-based XML layout tree of the running app screen. Optional screenshot included for vision models."
    override val riskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult {
        val xml = accessibilityEngine.dumpSemanticTreeXml()
        return ToolResult("inspect_ui", true, xml, 35)
    }
}

class InteractUiTool(private val accessibilityEngine: AccessibilityEngine) : AgentTool {
    override val name = "interact_ui_element"
    override val description = "Performs clicks, text typing, or gestures on UI elements using element resource IDs or text labels."
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult {
        return ToolResult("interact_ui", true, "Action successfully dispatched to Live Canvas", 45)
    }
}
```

---

## 7. Agent Orchestration Engine, Policy & Event-Sourced WAL

### 7.1 FSM State Model & Autonomy Policy
```kotlin
// agent-core/src/commonMain/kotlin/com/agent/code/core/fsm/AgentState.kt
data class ToolCall(val id: String, val toolName: String, val argumentsJson: String)

sealed interface AgentState {
    object Idle : AgentState
    data class Planning(val taskId: String, val thinkingProcess: String) : AgentState
    data class ExecutingTool(val taskId: String, val toolCall: ToolCall, val isStreamingOutput: Boolean) : AgentState
    data class AwaitingHumanApproval(val taskId: String, val toolCall: ToolCall, val riskLevel: RiskLevel, val justification: String) : AgentState
    data class Verifying(val taskId: String, val command: String) : AgentState
    data class Reflecting(val taskId: String, val attempt: Int, val maxAttempts: Int, val errorTrace: String) : AgentState
    data class Success(val taskId: String, val summary: String, val modifiedFiles: List<VirtualPath>) : AgentState
    data class Error(val taskId: String, val fatalCause: String) : AgentState
}

// agent-core/src/commonMain/kotlin/com/agent/code/core/policy/AutonomyPolicy.kt
enum class AutonomyLevel { FULL_AUTONOMY, MICRO_AGENTIC }

data class AutonomyPolicy(
    val mode: AutonomyLevel = AutonomyLevel.FULL_AUTONOMY,
    val autoApproveFileEdits: Boolean = true,
    val autoApproveReadCommands: Boolean = true,
    val autoApproveSafeBuilds: Boolean = true,
    val maxAutoFixAttempts: Int = 5,
    val haltOnDestructiveCommands: Boolean = true
)
```

### 7.2 Event-Sourced Write-Ahead Log (WAL)

**Current implementation (M0.5):**
```kotlin
// agent-core/src/commonMain/kotlin/com/agent/code/core/journal/AgentEvent.kt
sealed interface AgentEvent {
    val eventId: Long
    val taskId: String
    val timestampMs: Long

    data class TaskStarted(override val eventId: Long, override val taskId: String, override val timestampMs: Long, val goal: String) : AgentEvent
    data class ToolExecutionRequested(override val eventId: Long, override val taskId: String, override val timestampMs: Long, val toolCall: ToolCall) : AgentEvent
    data class ToolExecutionFinished(override val eventId: Long, override val taskId: String, override val timestampMs: Long, val result: ToolResult) : AgentEvent
    data class FilePatchApplied(override val eventId: Long, override val taskId: String, override val timestampMs: Long, val path: VirtualPath, val diff: String) : AgentEvent
    data class TaskSucceeded(override val eventId: Long, override val taskId: String, override val timestampMs: Long, val summary: String) : AgentEvent
}
```

**M5.1 upgrade — add `TokenChunkReceived` for streaming LLM token tracking:**
```kotlin
// Planned addition to AgentEvent sealed interface
data class TokenChunkReceived(
    override val eventId: Long,
    override val taskId: String,
    override val timestampMs: Long,
    val delta: String
) : AgentEvent
```

> **Migration note:** `FsmStateReconstructor.replay` gains a branch for `TokenChunkReceived` — accumulates token text for `Planning` state reconstruction. Registered in `eventJson` polymorphic module.

### 7.3 Bounded Atomic Step Execution
```kotlin
// agent-core/src/commonMain/kotlin/com/agent/code/core/fsm/AgentOrchestrator.kt
sealed interface StepResult {
    object TaskFinished : StepResult
    object StepCompletedMoreWorkPending : StepResult
    data class BlockedOnApproval(val approvalId: String) : StepResult
    data class FatalError(val reason: String) : StepResult
}

class AgentOrchestrator(
    private val journal: AgentEventJournal,
    private val lockCoordinator: TaskLockCoordinator,
    private val governor: AdaptivePowerGovernor
) {
    suspend fun executeSingleStep(taskId: String): StepResult {
        val currentState = journal.recoverState(taskId)
        if (currentState is AgentState.Success || currentState is AgentState.Error) {
            return StepResult.TaskFinished
        }

        return when (currentState) {
            is AgentState.Planning -> processPlanningStep(taskId, currentState)
            is AgentState.ExecutingTool -> processToolExecutionStep(taskId, currentState)
            is AgentState.Verifying -> processVerificationStep(taskId, currentState)
            is AgentState.Reflecting -> processReflectionStep(taskId, currentState)
            is AgentState.AwaitingHumanApproval -> StepResult.BlockedOnApproval(currentState.toolCall.id)
            is AgentState.Idle -> StepResult.TaskFinished
        }
    }

    suspend fun executeStepPaced(taskId: String): StepResult {
        when (governor.currentProfile.value) {
            OperatingProfile.TURBO_PLUGGED -> return executeSingleStep(taskId)
            OperatingProfile.BALANCED_BATTERY -> {
                val result = executeSingleStep(taskId)
                delay(50L) // Thermal dissipation pacing
                return result
            }
            OperatingProfile.ECO_PRESERVATION -> {
                delay(500L)
                return executeSingleStep(taskId)
            }
        }
    }
}
```

### 7.4 Universal Tokenizer & Prompt Caching Strategy
```kotlin
// agent-core/src/commonMain/kotlin/com/agent/code/core/token/UniversalTokenizer.kt
enum class TokenizerEncoding { CLAUDE_TIKTOKEN, OPENAI_CL100K, LLAMA_BPE }

interface UniversalTokenizer {
    fun countTokens(text: String, encoding: TokenizerEncoding): Int
    fun truncateToTokenLimit(text: String, maxTokens: Int, encoding: TokenizerEncoding): String
}
```

```text
Prompt Payload Structure (Strict Order for Warm Prefix Caching)
┌────────────────────────────────────────────────────────────────────────────────┐
│ [STATIC 1] System Persona Rules & Global System Constraints                    │
├────────────────────────────────────────────────────────────────────────────────┤
│ [STATIC 2] Repository AST Symbol Index & Dependency Graph                      │
├────────────────────────────────────────────────────────────────────────────────┤
│ [DYNAMIC 1] Task Context Container (Kanban Card Instructions & PLAN.md)        │
├────────────────────────────────────────────────────────────────────────────────┤
│ [DYNAMIC 2] Active Working Files (Rope Text Buffer)                            │
├────────────────────────────────────────────────────────────────────────────────┤
│ [DYNAMIC 3] Sliding Tool Execution Output Logs (Conflated Head/Tail Truncated) │
└────────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Unified LLM Provider & Network Subsystem

```kotlin
// provider-subsystem/src/commonMain/kotlin/com/agent/code/provider/LlmProvider.kt
data class ChatMessage(val role: Role, val content: String)
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class LlmRequest(
    val modelId: String,
    val messages: List<ChatMessage>,
    val tools: List<AgentTool> = emptyList(),
    val temperature: Float = 0.2f,
    val maxTokens: Int = 8192
)

sealed interface LlmEvent {
    data class ReasoningChunk(val text: String) : LlmEvent
    data class ContentChunk(val text: String) : LlmEvent
    data class ToolCallChunk(val id: String, val name: String, val jsonArgsDelta: String) : LlmEvent
    data class UsageReport(val promptTokens: Int, val completionTokens: Int) : LlmEvent
    data class SystemWarning(val code: Int, val message: String) : LlmEvent
}

interface LlmProvider {
    val providerId: String
    val displayName: String
    fun streamCompletion(request: LlmRequest): Flow<LlmEvent>
    suspend fun healthCheck(): Result<List<String>>
}

// provider-subsystem/src/commonMain/kotlin/com/agent/code/provider/ResilientSseClient.kt
class ResilientSseClient(
    private val client: HttpClient,
    private val maxRetries: Int = 5
) {
    fun streamWithRetry(request: LlmRequest): Flow<LlmEvent> = channelFlow {
        var attempt = 0
        var backoffMs = 1000L

        while (attempt < maxRetries) {
            try {
                client.serverSentEvents(request) {
                    // Stream chunks
                }
                break
            } catch (e: Exception) {
                attempt++
                if (attempt >= maxRetries) throw e
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }
}
```

### 8.1 Hierarchical Model Cost Router
```kotlin
// provider-subsystem/src/commonMain/kotlin/com/agent/code/provider/HierarchicalModelRouter.kt
enum class TaskComplexity { LOW_LINT_FORMAT, MEDIUM_CODE_EDIT, HIGH_ARCHITECTURAL_PLAN }

class HierarchicalModelRouter(
    private val providerRegistry: ProviderRegistry,
    private val circuitBreaker: CircuitBreaker
) {
    fun selectModel(complexity: TaskComplexity): LlmProvider {
        return when (complexity) {
            TaskComplexity.LOW_LINT_FORMAT -> {
                providerRegistry.getProvider("deepseek-coder")
                    ?: providerRegistry.getProvider("claude-3-5-haiku")
                    ?: providerRegistry.getProvider("qwen-2.5-coder")
                    ?: providerRegistry.getProvider("omniroute-fast")!!
            }
            TaskComplexity.MEDIUM_CODE_EDIT -> {
                providerRegistry.getProvider("gpt-4o-mini")
                    ?: providerRegistry.getProvider("claude-3-5-sonnet")
                    ?: providerRegistry.getProvider("omniroute-mid")!!
            }
            TaskComplexity.HIGH_ARCHITECTURAL_PLAN -> {
                providerRegistry.getProvider("claude-3-7-sonnet")
                    ?: providerRegistry.getProvider("deepseek-r1")
                    ?: providerRegistry.getProvider("gpt-4o")
                    ?: providerRegistry.getProvider("omniroute-deep")!!
            }
        }
    }
}
```

---

## 9. AI-Native Kanban & Task Engine

```text
┌──────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   BACKLOG    ├────►│   PLANNING   ├────►│ IN PROGRESS  ├────►│ VERIFICATION ├────►│ HUMAN REVIEW ├────►│  DONE/MERGED │
│ User Prompts │     │ Agent builds │     │ Agent edits  │     │ Runs Linter/ │     │ Human checks │     │ Merged to    │
│ & Requirements│    │ PLAN.md      │     │ code in      │     │ Compiler /   │     │ diffs /      │     │ main branch  │
│              │     │              │     │ Worktree     │     │ Unit Tests   │     │ approvals    │     │              │
└──────────────┘     └──────────────┘     └──────────────┘     ───────┬──────┘     └──────────────┘     └──────────────┘
                                                                      │
                                                            Tests Failed (Auto-Regress)
                                                                      │
                                                                      ▼
                                                            Back to IN PROGRESS (Fixing)
```

### 9.1 Self-Management MCP Tools
Agents advance the Kanban board using dedicated MCP tools:
*   `claim_next_task(agentId: String)`: Claims next unblocked card in `BACKLOG` and transitions to `PLANNING`.
*   `update_task_plan(taskId: String, planMarkdown: String)`: Attaches plan artifact and transitions to `IN_PROGRESS`.
*   `submit_task_for_verification(taskId: String)`: Triggers automated build/linter/UI test suite in `VERIFICATION`.

---

## 10. Mission Control UI & Conflated Telemetry Engine

**Current implementation (M3):** Manual buffering with spin-lock mutex, not `Channel` + `.chunked()`.
```kotlin
// agent-core/src/commonMain/kotlin/com/agent/code/core/journal/TelemetryEngine.kt
sealed interface LogEntry {
    val timestampMs: Long
    data class AgentThought(override val timestampMs: Long, val markdown: String) : LogEntry
    data class ToolCallStarted(override val timestampMs: Long, val toolName: String, val args: String) : LogEntry
    data class TerminalStream(override val timestampMs: Long, val line: String, val isError: Boolean) : LogEntry
    data class SystemWarning(override val timestampMs: Long, val message: String) : LogEntry
}

class TelemetryEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val frameMs: Long = 50L
) {
    private val _frames = MutableSharedFlow<List<LogEntry>>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val frames: Flow<List<LogEntry>> = _frames.asSharedFlow()

    private val pending = mutableListOf<LogEntry>()
    private val lock = Mutex()

    fun emit(entry: LogEntry) {
        // Spin-lock, add to pending, schedule flush after frameMs delay
    }

    fun flush() {
        // Snapshot pending → emit to _frames, clear
    }
}
```

---

## 11. Android Direct-APK Resilient Foreground Service

**Current implementation (M4):** Locks + notification + watchdog alarm. No `serviceScope` coroutine launch.
```kotlin
// androidApp/src/main/kotlin/com/agent/code/service/ResilientAgentForegroundService.kt
class ResilientAgentForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildPersistentNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireLocks()
        scheduleWatchdog()
        return START_STICKY
    }
    // ... lock acquire/release, notification channel, watchdog alarm
}
```

---

## 12. Security, Hardening & Safety Guardrails

### 12.1 Non-Interactive Git Auth (Injection-Free)
```kotlin
// workspace-engine/src/commonMain/kotlin/com/agent/code/git/GitAuthWrapper.kt
class GitAuthWrapper(
    private val credentialsVault: SecureVault,
    private val fs: FileSystemProvider
) {
    suspend fun <T> withEphemeralCredentials(block: suspend (askPassPath: VirtualPath) -> T): T {
        val pat = credentialsVault.getKey("GITHUB_PAT") ?: throw SecurityException("No PAT found")
        val runId = uuid()
        val secretPath = VirtualPath.of("/data/data/com.app/cache/.git-secret-$runId.txt")
        val scriptPath = VirtualPath.of("/data/data/com.app/cache/git-askpass-$runId.sh")
        
        // 1. Write secret as data file (Zero shell interpolation risk)
        fs.writeFile(secretPath, pat)
        
        // 2. Strict GIT_ASKPASS contract dispatching on $1 prompt
        val scriptContent = """
            #!/bin/sh
            case "${'$'}1" in
                *Username*) echo "oauth2" ;;
                *Password*) cat '${secretPath.rawPath}' ;;
            esac
        """.trimIndent()
        
        fs.writeFile(scriptPath, scriptContent)
        
        return try {
            block(scriptPath)
        } finally {
            if (fs.exists(scriptPath)) fs.delete(scriptPath)
            if (fs.exists(secretPath)) fs.delete(secretPath)
        }
    }
}

// agent-core/src/commonMain/kotlin/com/agent/code/core/security/SecureVault.kt
expect class SecureVault {
    suspend fun storeKey(alias: String, secret: String)
    suspend fun getKey(alias: String): String?
    suspend fun deleteKey(alias: String)
}
```

### 12.2 Financial & Resource Circuit Breakers

**Current implementation (M1):** Simple provider-id denylist.
```kotlin
// agent-core/src/commonMain/kotlin/com/agent/code/core/tools/CircuitBreaker.kt
class CircuitBreaker(private val openFor: Set<String> = emptySet()) {
    fun isOpen(providerId: String): Boolean = providerId in openFor
}
```

**M5.3 upgrade — add `TaskSafetyBudget` with cost/tool-count/time tracking:**
```kotlin
// Planned: agent-core/src/commonMain/kotlin/com/agent/code/core/tools/CircuitBreaker.kt
data class TaskSafetyBudget(
    val maxCostUsd: Double = 1.50,
    val maxToolCallsCount: Int = 40,
    val maxExecutionTimeMs: Long = 10 * 60 * 1000L
)

class BudgetTrackingCircuitBreaker(
    private val providerOpenFor: Set<String> = emptySet(),
    private val budget: TaskSafetyBudget = TaskSafetyBudget()
) {
    private var currentCostUsd = 0.0
    private var toolCallsCount = 0
    private val startTimeMs = Clock.System.now().toEpochMilliseconds()

    fun isOpen(providerId: String): Boolean = providerId in providerOpenFor

    fun trackUsage(cost: Double) {
        currentCostUsd += cost
        if (currentCostUsd >= budget.maxCostUsd) throw CircuitBreakerException("Budget Limit Exceeded ($${currentCostUsd})")
    }

    fun incrementToolCall() {
        toolCallsCount++
        if (toolCallsCount >= budget.maxToolCallsCount) throw CircuitBreakerException("Tool Cap Reached ($toolCallsCount calls)")
    }

    fun checkTimeBudget() {
        val elapsed = Clock.System.now().toEpochMilliseconds() - startTimeMs
        if (elapsed >= budget.maxExecutionTimeMs) throw CircuitBreakerException("Time Budget Exceeded (${elapsed}ms)")
    }
}

class CircuitBreakerException(message: String) : Exception(message)
```

> **Migration note:** `HierarchicalModelRouter` already uses `CircuitBreaker.isOpen()` — `BudgetTrackingCircuitBreaker` extends it. `AgentOrchestrator.runTool` calls `incrementToolCall()` after each tool dispatch. LLM provider `streamCompletion` calls `trackUsage` with token cost from `UsageReport`. `executeStepsUntilDone` calls `checkTimeBudget()` each iteration.

### 12.3 Runtime Crash Diagnostics & Doc Fetcher Tools

**Current implementation (M5):** `RuntimeDiagnosticsTool` only.
```kotlin
// workspace-engine/src/commonMain/kotlin/com/agent/code/tools/RuntimeDiagnosticsTool.kt
class RuntimeDiagnosticsTool : AgentTool {
    override val name = "read_runtime_diagnostics"
    override val description = "Reads recent runtime crash stack traces, Android Logcat error dumps, or application error logs."
    override val riskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult {
        val output = processRunner.run(listOf("logcat", "-d", "*:E"))
        return ToolResult("diagnostics", true, output.getOrNull() ?: "No errors found", 50)
    }
}
```

**M5.3 upgrade — add `FetchDocTool`:**
```kotlin
// Planned: workspace-engine/src/commonMain/kotlin/com/agent/code/tools/FetchDocTool.kt
class FetchDocTool(private val httpClient: HttpClient) : AgentTool {
    override val name = "fetch_documentation"
    override val description = "Fetches latest library docs or README markdown from GitHub/web to verify API signatures."
    override val riskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult {
        val obj = try {
            Json.parseToJsonElement(argumentsJson).jsonObject
        } catch (_: Exception) {
            return ToolResult(name, false, "invalid arguments json", 0L)
        }
        val url = obj["url"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(name, false, "missing url", 0L)
        return try {
            val response = httpClient.get(url).bodyAsText()
            ToolResult(name, true, response, 120)
        } catch (e: Exception) {
            ToolResult(name, false, "fetch failed: ${e.message}", 120)
        }
    }
}
```

> **Migration note:** `FetchDocTool` needs `ktor-client` (or `java.net.HttpURLConnection` for minimal deps). Register in `McpHost` or `MissionControlMcpServer` tool map alongside `read_file`/`apply_diff_patch`. Agent uses it to self-verify API signatures when uncertain about a library's current interface.

---

## 13. Complete Database Specification (SQLDelight Schemas)

```sql
-- data-layer/src/commonMain/sqldelight/database/MissionControl.sq

CREATE TABLE KanBanTask (
    id TEXT PRIMARY KEY NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    status TEXT NOT NULL, -- 'BACKLOG', 'PLANNING', 'IN_PROGRESS', 'VERIFYING', 'REVIEW', 'DONE'
    assignedAgentId TEXT,
    worktreePath TEXT,
    parentTaskId TEXT,
    priority INTEGER NOT NULL DEFAULT 1,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL
);

CREATE TABLE TaskDependency (
    taskId TEXT NOT NULL,
    dependsOnTaskId TEXT NOT NULL,
    PRIMARY KEY (taskId, dependsOnTaskId)
);

CREATE TABLE TaskArtifact (
    id TEXT PRIMARY KEY NOT NULL,
    taskId TEXT NOT NULL,
    artifactType TEXT NOT NULL, -- 'PLAN_MD', 'TEST_LOG', 'DIFF_PATCH', 'UI_TREE_SNAPSHOT'
    content TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    FOREIGN KEY (taskId) REFERENCES KanBanTask(id) ON DELETE CASCADE
);

CREATE TABLE AgentJournalEvent (
    eventId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    taskId TEXT NOT NULL,
    eventType TEXT NOT NULL,
    payloadJson TEXT NOT NULL,
    timestampMs INTEGER NOT NULL
);

CREATE TABLE ProviderProfile (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    protocol TEXT NOT NULL, -- 'OPENAI_COMPATIBLE', 'OPENCODE_NATIVE', 'ANTHROPIC', 'GEMINI'
    baseUrl TEXT NOT NULL,
    keyVaultAlias TEXT NOT NULL, -- Stored in SecureVault, never plaintext in DB
    defaultModel TEXT NOT NULL,
    isEnabled INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE AstNodeIndex (
    nodeUuid TEXT PRIMARY KEY NOT NULL,
    filePath TEXT NOT NULL,
    symbolQualifiedName TEXT NOT NULL,
    nodeType TEXT NOT NULL,
    startByte INTEGER NOT NULL,
    endByte INTEGER NOT NULL,
    lastModifiedMs INTEGER NOT NULL
);
```

---

## 14. Phased Production Execution Roadmap

```text
┌──────────────────────────────────────────────────────────────────────────────────┐
│                           INCREMENTAL DERISKING ROADMAP                          │
├──────────────────┬───────────────────────────────────────────────────────────────┤
 │ M0.5 (BOOTSTRAP)│ Self-Contained Spine: FSM + WAL(in-mem) + MCP loop + SSE JSON │
 │ (Prove it thinks)│ parser + Cost Router + Kanban + 50ms Telemetry. JVM-testable. │
 ├──────────────────┼───────────────────────────────────────────────────────────────┤
 │ MILESTONE 1      │ Headless Core: libgit2 NDK + Shizuku Elevation + WAL Journal │
 │ (The Bedrock)    │ EnergyAwareDispatchers + MCP Server + OmniRoute SSE Client.   │
 ├──────────────────┼───────────────────────────────────────────────────────────────┤
│ MILESTONE 2      │ Multi-Agent Concurrency: Git Sparse Worktrees + Auto-Squash   │
│ (Scale)          │ Lock Coordinator (Waiters) + 4-Tier Semantic Conflict Funnel. │
├──────────────────┼───────────────────────────────────────────────────────────────┤
│ MILESTONE 3      │ UI & Cost Routing: CMP Shell + 50ms Conflated Telemetry Stream│
│ (UX & Pacing)    │ Streaming JSON State Machine + Adaptive Power Governor.       │
├──────────────────┼───────────────────────────────────────────────────────────────┤
│ MILESTONE 4      │ Android Live Testing: Resilient FGS + Geometric Layout Oracle │
│ (Live Testing)   │ Dual-Mode Accessibility Engine (Text XML & Native Gestures).  │
├──────────────────┼───────────────────────────────────────────────────────────────┤
│ MILESTONE 5      │ Security & Hardening: Non-Interactive Git Auth (Injection-Free│
│ (Production)     │ Visual 3-Way Merge Screen + Hardware SecureVault (KeyStore).  │
├──────────────────┼───────────────────────────────────────────────────────────────┤
│ M5.1 (INTERFACES)│ FileSystemProvider → suspend + applyPatch/walkTree;           │
│                  │ ProcessRunner → ProcessConfiguration + streaming;             │
│                  │ AgentEvent.TokenChunkReceived; Kanban HUMAN_REVIEW doc.       │
├──────────────────┼───────────────────────────────────────────────────────────────┤
│ M5.2 (PLATFORM)  │ AdaptivePowerGovernor full thermal+battery impl;              │
│                  │ PrivilegedElevationManager Shizuku phantom-process bypass.    │
├──────────────────┼───────────────────────────────────────────────────────────────┤
│ M5.3 (TOOLS)     │ MissionControlMcpServer full JSON-RPC + UI tool registry;    │
│                  │ FetchDocTool; CircuitBreaker TaskSafetyBudget tracking.       │
├──────────────────┼───────────────────────────────────────────────────────────────┤
│ M5.4 (DOC)       │ Fix phantom paths (platform-android, shared/); README update; │
│                  │ Dependency DAG correction; TelemetryEngine actual impl.       │
└──────────────────┴───────────────────────────────────────────────────────────────┘
```

---