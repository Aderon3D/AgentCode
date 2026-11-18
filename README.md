# AgentCode

> Multi-Agent Autonomous Programming & Live Studio Platform.
> Kotlin Multiplatform (Android 12+ primary, Windows 11 secondary),
> Compose Multiplatform, Native C/C++ (NDK) in later milestones.

A self-contained, on-device AI agent runtime with FSM orchestration, multi-agent
concurrency, tool dispatch, and a live Mission Control UI. Proves autonomous
programming end-to-end on real Android hardware.

See [`Development_Doc.md`](./Development_Doc.md) for the full master technical
design (TDD V14.1) and the module layout.

## Project layout

- `agent-core/` — Core FSM, journal, policy, power, tools, lock, MCP, workspace interfaces.
- `provider-subsystem/` — LLM provider, SSE client, streaming JSON, cost router.
- `workspace-engine/` — Git backends, file system, accessibility, UI tools, lock impls.
- `app-ui/` — CMP dashboard, kanban, bootstrap, telemetry, and the cross-module host tests.
- `androidApp/` — Android launcher, foreground service, demo screens.

## Status

Current release: **r42**. Milestones, their evidence and the next steps live in
[`docs/ROADMAP.md`](./docs/ROADMAP.md) — the only place status is written down.

## Building & testing

```bash
./gradlew :androidApp:assembleDebug          # Android debug APK
./gradlew :agent-core:testAndroidHostTest :provider-subsystem:testAndroidHostTest \
                 :workspace-engine:testAndroidHostTest :app-ui:testAndroidHostTest  # host tests, all modules
```

## CI / security gates

All changes reach `main` only through a reviewed PR. The following automated
guards run on every PR and on every push to `main`:

- **Build & Test** — compile + host tests (`build.yml`)
- **CodeQL Advanced** — security-and-quality scan (`codeql.yml`)
- **Dependency Review** — vulnerable-dependency bot (`dependency-review.yml`)
- **Dependency Submission** — Gradle dependency graph snapshot (`dependency-submission.yml`)
- **OSV Scanner** — CVE scan of the dependency graph (`osv-scanner.yml`)
- **Secret Scan** — Gitleaks secret detection (`secret-scanning.yml`)
- **Dependabot** — weekly dependency & action updates (`dependabot.yml`)

Branch protection + required status checks are documented in
[`SECURITY.md`](./SECURITY.md) and [`CONTRIBUTING.md`](./CONTRIBUTING.md).

> **Note:** This project is AI-assisted. Expect rough edges; the CI gates above
> exist specifically to catch errors before they reach `main`.
