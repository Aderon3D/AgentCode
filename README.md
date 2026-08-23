# Aderon3D (AgentCode)

> Multi-Agent Autonomous Programming & Live Studio Platform.
> Kotlin Multiplatform (Android 12+ primary, Windows 11 secondary),
> Compose Multiplatform, Native C/C++ (NDK) in later milestones.

This repository currently holds the **M0.5 — Self-Contained Bootstrap Spine**:
a pure-Kotlin, dependency-light vertical slice that proves the architecture
end-to-end and is fully unit-testable on the JVM host test target.

See [`Development_Doc.md`](./Development_Doc.md) for the full master technical
design (TDD V14.1) and the M0.5 package layout under `com.agent.code`.

## Project layout

- `shared/` — Kotlin Multiplatform module. M0.5 lives here as packages inside
  `commonMain` + `androidMain` + `commonTest` (no new Gradle subprojects yet).
- `androidApp/` — Android launcher (namespace `com.agent.code`).

## Building & testing

```bash
./gradlew :androidApp:assembleDebug        # Android debug APK
./gradlew :shared:testAndroidHostTest      # M0.5 deterministic host tests (green gate)
```

## CI / security gates

All changes reach `main` only through a reviewed PR. The following automated
guards run on every PR and on every push to `main`:

- **Build & Test** — compile + M0.5 host tests (`build.yml`)
- **CodeQL Advanced** — security-and-quality scan (`codeql.yml`)
- **Dependency Review** — vulnerable-dependency bot (`dependency-review.yml`)
- **OSV Scanner** — CVE scan of the dependency graph (`osv-scanner.yml`)
- **Secret Scan** — Gitleaks secret detection (`secret-scanning.yml`)
- **Dependabot** — weekly dependency & action updates (`dependabot.yml`)

Branch protection + required status checks are documented in
[`SECURITY.md`](./SECURITY.md) and [`CONTRIBUTING.md`](./CONTRIBUTING.md).

> **Note:** This project is AI-assisted. Expect rough edges; the CI gates above
> exist specifically to catch errors before they reach `main`.
