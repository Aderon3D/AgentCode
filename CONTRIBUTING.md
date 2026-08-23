# Contributing to Aderon3D

M0.5 and later versions of Aderon3D are developed **branch-first**. `main` is
protected: no code is ever pushed directly to it.

## Workflow

1. Create a branch off `main`:
   `git checkout -b feat/<short-name>` or `fix/<short-name>`.
2. Make your change. Keep it focused; M0.5 changes live inside the `:shared`
   module (`commonMain` + `androidMain` + `commonTest`) per `Development_Doc.md`.
3. Open a Pull Request against `main`.
4. The following **required checks** must pass before merge:
   - **Build & Test** — compiles `:shared` + `:androidApp` and runs
     `./gradlew :shared:testAndroidHostTest` (the deterministic M0.5 spine).
   - **CodeQL Advanced** — security-and-quality scan of Kotlin/Java + workflows.
   - **Dependency Review** — no high-severity vulnerable deps introduced.
   - **OSV Scanner** — no known CVEs in the dependency graph.
   - **Secret Scan** — no secrets committed.
5. A member of `@Aderon3D/maintainers` must approve. Conversation must be
   resolved. Then squash-merge.

## Local pre-push check

Run the same gate locally before opening a PR:

```bash
./gradlew :shared:compileKotlinAndroid :androidApp:assembleDebug :shared:testAndroidHostTest --no-daemon
```

Keeping this green locally avoids wasting CI minutes on AI-generated code that
does not even compile.
