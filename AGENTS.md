# AgentCode — agent notes

## Toolchain

Gradle wrapper 9.7.1, JDK 21. On this device there is no `java` on `PATH`, so
prefix every Gradle command with the JDK home:

```
JAVA_HOME=/opt/java/jdk-21.0.12.1+1 ./gradlew <task>
```

## Kotlin static analysis — use detekt, not fossil

The `fossil` MCP does **not** parse Kotlin (`.kt` / `.kts`) — it returns
`total_nodes: 0`, which looks like a clean result but isn't. For Kotlin quality
use detekt (`dev.detekt`, wired into every module):

```
JAVA_HOME=/opt/java/jdk-21.0.12.1+1 ./gradlew detekt
```

- Reports: `<module>/build/reports/detekt/detekt.{html,md,sarif,xml}` — read the
  report; `ignoreFailures = true` means the exit code stays 0 even with findings
  (so detekt never breaks `build`/`check`).
- Tuning lives in `config/detekt/detekt.yml`, merged over detekt's default config
  (`buildUponDefaultConfig = true`). Convention noise is disabled there
  (`MagicNumber`, `WildcardImport`, `FunctionNaming` PascalCase for `@Composable`);
  the remaining findings are real signals: `TooGenericExceptionCaught`,
  `CyclomaticComplexMethod`, `MaxLineLength`, `ReturnCount`, `UnusedParameter`,
  `SwallowedException`, `MayBeConstant`.
- Test source sets are excluded — detekt runs on production code only.
- Android Lint is also available for Android modules: `./gradlew lint`.

## Tool-use calibration

Evidence from session audits (see repo history): plain grep/bash dominate while
the MCP analysis tools sit idle. Follow these thresholds:

- **Structure/reachability questions → graph tools first** (`search_graph`,
  `trace_path`, `query_graph`, `get_architecture`). `grep`/`rg` is for literals
  and coverage gaps only, never for "where is X used / how does A reach B".
  Past-session recall and recorded decisions → `mempalace_*`, not the code graph.
- **Stuck after 2 failed attempts on a hard problem → call `advice_ask`** with the
  context and what you tried (it consults a bigger Gemini model; 5-min timeout).
- **Kotlin quality checks → `detekt` MCP tool**, not a hand-rolled
  `./gradlew detekt` bash call (same config, structured findings).

## Build

```
JAVA_HOME=/opt/java/jdk-21.0.12.1+1 ./gradlew :androidApp:assembleDebug
```

Host tests live in every module; run them all before pushing (CI does):

```
JAVA_HOME=/opt/java/jdk-21.0.12.1+1 ./gradlew \
  :agent-core:testAndroidHostTest \
  :provider-subsystem:testAndroidHostTest \
  :workspace-engine:testAndroidHostTest \
  :app-ui:testAndroidHostTest
```
