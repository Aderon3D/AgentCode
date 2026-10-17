# Running opencode inside an Android app — everything we learned (rounds 1–15)

**Purpose:** a future session (or person) hitting this problem class should read
this and be productive in 5 minutes, not re-derive 6 days of debugging.

Sources (in order of depth): this file →
`thoughts/2026-10-05-opencode-elfloader-issues.md` (round-by-round log with
exact code/markers) → `logs/` (device evidence, `chat-paste-*.txt` = Oct 6–9,
`7.txt`–`13.txt` = Oct 9–10) → `logs/REPORT-r12-ptrace-thread-ownership.md`
(kernel-verified ptrace root cause, written by a separate research session) →
`docs/GLIBC_WORKAROUND.md`,
`docs/AAPT2_AARCH64_WORKAROUND.md`, `docs/SETUP_NODE_MUSL.md`,
`docs/APK_SAVE_IDE_MODULE_BUG.md`, `ANDROIDIDE_BUILD_NOTES.md` → the chat DBs:
current `/opencode-home/1.18.31/.local/share/opencode/opencode.db` (main saga
session `ses_ef291a15fffex6xuqbU4uZnVOm` Oct 5→10, plus `ses_edacde5c…` "Debugging
app issues from live logs" Oct 10 = the r12 research pass) and the old version
`/opencode-home/1.17.8/.local/share/opencode/opencode.db` (pre-Oct-5 project
history: install-flow trace Sep 5, Android MVP audit Sep 4, free-model research
Sep 1, council MCP build Sep 8, session-recovery incident Sep 21 in the new DB).

---

## 1. Start here — the 5-minute orientation

**What works today:** APK `635c7c409ff7ab24b5dc3c90949bcb25` boots
`opencode serve` inside the app process (port 4096), passes `/api/health`,
and the agent goal field drives opencode's real session API with
`mimo-v2.6-flash-free` (fallback `space-bunny-free`). Copy Logs works in any
state. Debug build; user installs via m4coding IDE (we have no install path).

**Rebuild everything (3 commands):**

```bash
bash scripts/build-elfloader.sh                                  # libelfloader.so (NDK clang)
JAVA_HOME=/opt/java/jdk-21.0.12.1+1 ./gradlew :androidApp:assembleDebug
md5sum androidApp/build/outputs/apk/debug/androidApp-debug.apk \
       app/build/outputs/apk/debug/androidApp-debug.apk           # must match (mirror is required)
```

Both APK paths must exist — the IDE hardcodes `app/build/outputs/apk/debug/`
for install (`androidApp/build.gradle.kts` copies it). Never "clean up" the
mirror.

**Local smoke test without a device** (harness built from the same
`elfloader.c`, artifacts in `/data/user/0/com.m4coding.ide/cache/opencode/ldtest/`):

```bash
T=/data/user/0/com.m4coding.ide/cache/opencode/ldtest
C=agent-core/src/androidMain/c
J=/opt/java/jdk-17.0.20+8
gcc -O1 -pthread -o $T/harness $T/harness.c -I$C -I$J/include -I$J/include/linux -w
# then, with ABSOLUTE paths (child chdirs before opening):
#   A  harness libld.so glibc opencode work a1.log --version        -> exit 0
#   AH same + HARNESS_SECCOMP=1                                     -> exit 0
#   D  harness ... t_sigbus ...                                     -> exit 135
#   SA harness ... t_sigabrt ...                                    -> exit 109
```

`-pthread` is required since the r8 drain-thread link. Only absolute paths work.

**Verify a device log in seconds:** first line is the build stamp
(`elfloader: build rN <date>`). If the stamp is old, the run proves nothing —
a wasted round (Oct 9) happened exactly this way. Then grep:
`GETSIGINFO errno` (must be absent), `listening` (server bound),
`fault stop ... syscall=` (seccomp fixups landing), `hb state=` (child alive
and in which syscall).

**Probe the server locally** (python's HTTP stack segfaults silently in this
container — use curl):

```bash
$T/opencode serve --port 4099 &   # then curl http://127.0.0.1:4099/doc  (OpenAPI)
```

---

## 2. The history — 15 rounds, one root cause each

**Pre-history (Aug 24 – Sep 8, old 1.17.8 chat DB):** the app originally
installed opencode by downloading 4 glibc libs + `opencode.gz` from
`github.com/Aderon3D/AgentCode/releases/download/opencode-binary`, writing a
shell wrapper, and spawning `sh -c 'wrapper serve --port 4444 & echo $!'`
(MVP audit Sep 4; full flow trace Sep 5, which flagged the silent-failure
spots — HTTP status unchecked, PID-read swallow, Error state filtered out of
the poll loop — all since fixed or obsoleted by the native spawn). That design
never actually ran the glibc binary on device; it's what the Oct 5 elfloader
saga replaced.

| # | Date / build | Symptom | Root cause | Fix |
|---|---|---|---|---|
| Oct 5 | resolution log | SIGILL inside our own text | naked asm `enter_loader` hardcoded `x0`/`x1`; GCC inlines at `-O1+` and basic asm doesn't own registers | `noinline` + explicit `"r"(sp)`/`"r"(entry)` operands |
| Oct 5 | + same round | 62 MB re-download every launch | `.loader-v1` written, `.loader-v2` checked | one sentinel both sides; propagate write result (`.getOrThrow()`) |
| Oct 6 | device debug round | handler `GONE`, raw signal 11 | raw `rt_sigaction` got bionic's 24-byte `struct sigaction`; kernel needs 32 `{handler,flags,restorer,mask}` → adjacent BSS read as mask | explicit `struct ksa`, mask=0; dual register (bionic `sigaction()` then raw) |
| Oct 6 | fault-PC round | silent death in `init_tls()` | unknown — no fault PC available | delivery self-test (`*(int*)0x10=0`, handler detects + skips `pc+=4`) + `PTRACE_TRACEME` before jump to capture PC |
| Oct 7 | SIGSYS fix round | SIGSYS `code=1` kills child in rtld | Android's zygote seccomp allowlist traps `set_robust_list`(99) / `rseq`(293) — absent from bionic `SYSCALLS.TXT` | in-child SIGSYS handler writes `-ENOSYS` into `regs[0]` + tracer fixup on `si_code==SYS_SECCOMP` |
| Oct 8 | round 2–3 | signal 31 at worker threads, no stops | (2) first-stop `DETACH` un-traced the process; (3) `nativeCheck` never set `PTRACE_O_TRACECLONE` → clone children untraced → their trap hits disposition DFL | stay-traced (`CONT` not `DETACH` after fixups) + `SETOPTIONS(TRACECLONE\|EXITKILL)` + `waitpid(-1, __WALL)` |
| Oct 8 | round 4 | `ENOENT uv_os_homedir` → Bun exits | no `HOME` in app env and no passwd entry for the app uid | inject `HOME=<dirname of binary>`; also swallow our own SIGSTOP auto-attach stops |
| Oct 8 | round 5 | health timeout + `ServeError`, "storm" | storm is tkill-loop signals at futex/sigsuspend pcs; forensics added (`tid=`/`spid=`/bt, breaker) | forensics round — but storm signal was mislabeled (see round 7) |
| Oct 8 | round 6 | storm persists after fixes | tracer re-injects the caught signal every delivery-stop → receivers never progress | stop 1 re-delivers, stops 2..60 discarded, cap 60 → kill |
| Oct 9 | round 7 (r7) | storm persists, `st=0` everywhere | **`sig=30` is SIGPWR, not SIGABRT(6)** — the breaker keyed on the wrong signal number so it could never fire | build stamps (`elfloader: build r7 …`); SIGPWR fast path (stops 4+ re-deliver with no /proc reads), cap 500 |
| Oct 9 | round 8 (r8) | storm dead, child never reaches `listening` | **THE big one:** Kotlin polled `checkChild` every 100 ms; between polls every ptrace stop parks the child up to 100 ms — Bun's tkill wake-loop needs µs round-trips and livelocked | **dedicated drain thread** with *blocking* `waitpid`; `nativeCheck` only reads an atomic |
| Oct 9 | round 9 (r8 log → r9) | storm dead, but child never reaches `listening` | dedicated drain thread worked (0 SIGPWR vs 92) yet the child went silent after rtld version checks; the log couldn't say why | add `drain ev tid= st=` forensics + a backup WNOHANG sweep in `nativeCheck` → no permanent stall possible, whatever it is |
| Oct 9 | round 10 (r9 log → r10) | child still silent; **no `drain thread up` line at all** | `O_TRUNC` race: child's `open` erases lines the drain thread wrote first; the stall itself still unnamed | parent truncates before fork, child opens `O_APPEND`; `pthread_create` failure now logged; **2 s heartbeat** (`state=`/`wchan=`/`/proc/<pid>/stack`) → r10 log shows `hb state=t wchan=ptrace_stop` ×7 |
| Oct 9–10 | round 11 (r10 log → r11) | first stop caught, then `PTRACE_SETOPTIONS errno=3` from the drain pthread | ptrace from the drain **pthread** gets ESRCH while identical calls from the forking JNI thread worked | move `SETOPTIONS` to JNI thread (r11) — still ESRCH for everything else |
| Oct 10 | round 12 (r11 log → r12) | r11 same ESRCH, `TracerPid` = JNI thread | **kernel truth:** `ptrace_check_attach()` requires `child->parent == current` (`kernel/ptrace.c:269`); `ptrace_traceme` (:527) links the tracee to **the task that called fork()**. `waitpid` is per-process, `ptrace()` is per-task | `spawn_server_thread`: the same thread does fork + `drain_loop` + all ptrace. Single-waiter principle: nobody else may `waitpid` |
| Oct 10 | round 13 (r12 log) | r12 works! Server `listening`, health still times out | `rememberCoroutineScope()` = **Main dispatcher** → `HttpURLConnection` on UI thread → `NetworkOnMainThreadException` on every attempt, swallowed by `catch {}` | `withContext(Dispatchers.IO)` + surface `last health: <real error>` into the timeout message |
| Oct 10 | round 14 (r13 log) | `Cleartext HTTP traffic to 127.0.0.1 not permitted` | targetSdk 36 Network Security Config blocks cleartext — **loopback is not exempt** | `android:usesCleartextTraffic="true"` on `<application>` |
| Oct 10 | round 15 | server healthy but goal field does nothing | client called OpenAI-shaped `/api/chat*` which this server doesn't serve; unknown routes return the **SPA HTML with 200** → SSE parser emitted zero events → brain looped silently | rewired to the real session API (§5); always-available log export |
| Oct 11 | round 24 (r24) | on-device agent: `prompt accepted`, **no SSE events ever stream**; the serve path also hits an ENOTDIR bug (default instance dir resolves to the binary path, `serve` has no `--dir`) | the serve+SSE design is a fragile on-device dependency: a long-lived `serve` under ptrace + a live global SSE stream that only delivers to current subscribers (no replay), so any startup/timing gap = silent hang with an empty feed | **pivot the agent to `opencode run --format json`**: one-shot subprocess, flat JSON events on stdout, transient in-process server that exits with the run, tools auto-approved via `--auto`. `serve` kept only for the browser web UI. New: `OpenCodeManager.runPrompt()`, `parseRunEvent()`, starter plugin-only `opencode.json` (§3.7) |
| Oct 11 | round 25 (r24 log) | feed shows `run mimo-v2.6-flash-free ...` but **no `run --format json` spawn line ever appears**; the run subprocess never starts | **`stateMutex` deadlock**: `start()` holds the mutex for its entire duration (including `waitForServer`, up to 15s); `runPrompt` → `ensureInstalled()` tries to acquire the same mutex and blocks forever | `runPrompt` now does a non-blocking `fileSystem.exists(binary)` check instead of `ensureInstalled()`; added `runTimeoutMs` (120s) deadline to the offset-tail loop so a ptrace-stalled child can't hang the flow forever |
| Oct 12 | round 26 (r25 log) | deadlock gone (`state=Running`) but the feed still stalls at `run <model> ...`; log looked perfectly healthy | **two blind spots**: (1) `logDump()` only ever read `opencode.log`, but the run subprocess's stdout (JSON events + elfloader forensics) lands in `run-<taskId>.events` — a file nothing read, so the stall was invisible; (2) the starter `opencode.json` names 3 npm plugins, and `run` resolves those through npm on device — a hang/failure there emits zero events | `logDump()` now appends `run-<taskId>.events` and names it in the header; the agent path adds `--pure` (documented `run` flag) so no external plugins are loaded at all — plugins stay an enhancement, not a dependency (§3.7) |
| Oct 13 | round 27 (r26 log) | `--pure` run **spawns** (deadlock gone, `run --format json --auto --pure` line present) but stalls at `GETSIGINFO errno=3` then infinite `hb state=t wchan=ptrace_stop` | **two concurrent spawns, two drain threads, both `waitpid(-1)`**: `start()` (serve, pid 30021) and `runPrompt()` (run, pid 30030) each fork a child via `nativeSpawn` → `spawn_server_thread` → `drain_loop`. `waitpid(-1)` is process-scoped, so the two threads steal each other's child-stop events. When the serve thread reaps a run-child stop, `ptrace` returns ESRCH (only the *forking* thread can ptrace its child — elfloader.c:1003, the r8-r11 single-tracer rule), `PTRACE_CONT` also fails, and the run child parks in `ptrace_stop` forever while its own drain thread blocks in `waitpid` | `runPrompt()` now calls `stop()` before spawning — kill the web-UI server, leave one live child, one drain thread. Run mode is self-contained (no port, no SSE). **Root-caused + reproduced natively** (§3.8): `waitpid(-1)` is per-process, so the two drains steal each other's events. Fix: serialize — `runPrompt()` stops the serve child before spawning, and the loader now enforces one drain at a time (single-spawn gate). **Ceiling**: true concurrent spawns (multi-agent) need per-tid tracking; the r28 `waitpid(-pgid)` attempt was a regression (the kernel reports no ptrace-stops through a process-group wait) and was reverted |

Rounds through 12 were one problem (child survives and serves); rounds 13–15
were three different, increasingly boring client-side problems. Pattern:
**each layer only fails after the previous one is proven** — and each was
named in one run once the error surfaced instead of being swallowed. Round 24
was not a bug but a pivot: once the serve+SSE stack proved more moving parts
than the job needed, the agent dropped to the CLI the CI already trusts.

---

## 3. Deep platform knowledge (the part that must not be rediscovered)

### 3.1 Android seccomp (SIGSYS fix round, rounds 2-4)

- Since Android O, **zygote installs a seccomp allowlist that every app
  inherits**. Anything not listed in bionic's `SYSCALLS.TXT` gets
  `SECCOMP_RET_TRAP` → **SIGSYS with `si_code == 1` (`SYS_SECCOMP`)**.
  Confirmed blocked on device: `set_robust_list` (syscall **99**),
  `rseq` (**293**). `set_tid_address` is allowed — that narrows the rtld
  window to `{mmap, mmap, set_tid_address, set_robust_list, rseq}`.
- Filters compose by **ACTION precedence** (KILL > TRAP > ERRNO > USER_NOTIF
  > LOG > ALLOW), **never by install order**. A per-task `RET_ERRNO(ENOSYS)`
  shim cannot preempt zygote's TRAP. Seccomp is one-way (`NO_NEW_PRIVS`) —
  you can never install a *less* permissive filter.
- Kernel (`seccomp.c`, v6.12): `RET_TRAP` → `syscall_rollback` +
  `force_sig_seccomp(…, false)` = **catchable**, syscall returns −1 — our
  fixup model. `RET_KILL_THREAD && live>1` → `do_exit(SIGSYS)`, **uncatchable
  by ptrace** (no stop, no forensics) — the one failure class that cannot be
  instrumented.
- The fix that works: at the stop, write **`-ENOSYS` into `regs.regs[0]`**
  (tracer `SETREGSET`, or in-child SIGSYS handler via
  `uc_mcontext.regs[0]`). glibc tolerates ENOSYS on both 99 and 293
  (verified: boot reaches `--version` with them failing).
- **AOSP sigchain:** bionic `sigaction()` only records the handler in
  libsigchain's chain table; a raw `rt_sigaction` bypasses ART's trampoline
  and writes the kernel slot directly. Register **both** (bionic first, raw
  last) and read back the kernel slot to know what's really installed.
- **bionic's userland `struct sigaction` is 24 bytes** `{handler, flags,
  mask}`; the kernel's is 32 `{handler, flags, restorer, mask}`. Passing the
  bionic struct to raw `rt_sigaction` reads 8 adjacent BSS bytes as the mask
  and writes past the object on readback. **glibc's layout matches the
  kernel's — which is why the bug is invisible in the local harness.**

### 3.2 ptrace is per-TASK, waitpid is per-PROCESS (round 8 introduced, round 12 root-caused)

The single most expensive lesson (bug introduced at round 8, cost rounds 9–11, settled round 12):

- `kernel/ptrace.c:269` — `ptrace_check_attach()` requires
  `child->ptrace && child->parent == current`, else **`-ESRCH`**. Every
  request except TRACEME/ATTACH/SEIZE routes through it (:1435).
- `ptrace_traceme` (:527) links the tracee to **the task that called
  `fork()`**, not to the process.
- Consequence: **the thread that forks must also issue every subsequent
  `ptrace()`** (SETOPTIONS, GETSIGINFO, GETREGSET, CONT). A separate drain
  pthread can `waitpid` (process-scoped, sees everything) yet get `ESRCH` on
  every `ptrace()` — exactly the r10/r11 signature: stops visible, all
  servicing no-ops, child parked 14 s until `kill -9`.
- **Single-waiter principle:** only one thread may consume events. Two
  waiters race for the same stop; `nativeCheck` must never `waitpid` alongside
  the drain (keep it a read of an atomic).
- **Which `waitpid` selection even sees a tracee** (r28): a ptrace-stop is
  delivered only to the tracer and is **not** visible through a process-group
  wait. Against a traced child, `waitpid(-pgid, __WALL[|WUNTRACED])` returns
  **nothing** (no stop, no exit), `waitpid(tid)` sees one thread, and
  `waitpid(-1)` sees the whole tracee thread group (leader + `TRACECLONE`
  workers). Use `waitpid(-1)`.
- `PTRACE_O_TRACECLONE` must be set **before the first clone** (the first
  stop is the main thread's pre-clone pthread-init trap, so it lands in time).
  Without it, worker threads run untraced and their SIGSYS hits disposition.
- New clones deliver an auto-attach **SIGSTOP** (`st=0x137f`, event stop =
  `WSTOPSIG == SIGTRAP && status >> 16`): `CONT(0)` it — re-injecting the
  SIGSTOP parks the thread in a loop (round 6 bug).
- **Never `PTRACE_DETACH` after a fixup** if more traps are coming: detaching
  at the first seccomp trap un-traces the process; the next worker's trap then
  kills it (round 5). Stay attached.
- Kill-race artifact in logs: a fault line with **all-zero siginfo/regs** and
  no maps/bt = the task was already dying when `GETSIGINFO` ran (our own
  `kill -9` at timeout). Not a real stop.

### 3.3 Signal decoding and the Bun SIGPWR loop (rounds 7–10)

- **Never assume a signal number.** `sig=30` is **SIGPWR**, `SIGABRT` is **6**,
  `SIGSYS` is **31** on Linux/Android both. Three rounds keyed breakers on
  "the SIGABRT storm" while the log said `sig=30` — the breaker could never
  fire. Decode `st=` against a real table (`st = (sig << 8) | 0x7f` for a
  delivery-stop; `code=-6` = `SI_TKILL` = someone `tkill`ed it; `code=1` =
  `SYS_SECCOMP`).
- The loop itself: the child main thread `tkill`s **SIGPWR round-robin** at
  workers parked in futex / sigsuspend / `sched_yield` (`svc #0` pcs), with
  `SigCgt` bit set (a *returning* handler). This is the known upstream **Bun
  infinite self-signal loop** (Fedora issue family): when Bun's scheduler
  can't make progress it re-signal-loops. It is self-sustaining **only if
  signal delivery is slow** — see next point.
- **Why it only broke in the app:** under proot/harness events are serviced
  in µs; in the app, the child was only serviced when Kotlin polled every
  100 ms, so *every* stop cost up to 100 ms of receiver downtime → the wake
  loop never converged. A blocking drain thread made stops cost µs → the
  storm disappeared entirely (r7: 92 stops → r8: 0). **Latency of the tracer
  is itself a correctness property.**
- Fast-path discipline: forensics (maps/status/bt ≈ several ptrace calls) on
  the first few stops only; afterwards re-deliver with zero reads; cap and
  `PTRACE_KILL` above the cap so a non-converging loop fails in 100 ms
  instead of hanging 15 s.

### 3.4 ART / dalvik / debuggerd (Oct 5–6, the first rounds)

- ART's Java heap `[anon:dalvik-main space]` sits at `0x02000000–0x12000000` —
  **inside the ELF window the loader `MAP_FIXED`s over**. Eviction/mapping
  must account for it (we evict it; the old loader clobbered it → death).
- `adb logcat | grep "Fatal signal"` is empty for our child — debuggerd
  doesn't run for a task that jumped into rtld without `execve`. The faulting
  PC must be captured by *our* mechanism (`PTRACE_TRACEME` + `GETSIGINFO` +
  `GETREGSET(NT_PRSTATUS)`), which is also what proved every later stop.
- The child never `execve`s: it `mmap`s `ld.so` and jumps (`userland exec`).
  This is deliberate — it keeps `/proc/self/exe` correct for Bun's
  self-inspection (the `bun-termux-loader` project does the same for the same
  reason).

### 3.5 glibc-on-Android gaps (round 4, and pending)

- **No `HOME`, no `/etc/passwd` entry for the app uid** → glibc `getpwuid`
  fails → Bun's `uv_os_homedir` returns ENOENT → **Bun exits at startup**.
  Fix: inject `HOME=` (we use the binary's dirname). Next candidates if it
  moves: `TMPDIR` (Android has no writable `/tmp`).
- **No `/etc/resolv.conf`** → glibc `getaddrinfo` can't resolve → **the DNS
  wall**. We have *not* hit it because health is `127.0.0.1`; provider HTTPS
  calls (opencode.ai) will hit it on device. Field solutions: `shim_dns.c` /
  `getaddrinfo` shim via `LD_PRELOAD`, or drop a resolv.conf into the child's
  root (see `openclaw-android`, `claude-code-termux`).
- Never export `LD_LIBRARY_PATH` globally (poisons bionic children); never
  let an inherited `LD_PRELOAD` reach the glibc child — filter both in
  `child_main`; scope library paths to `ld.so --library-path` instead
  (`glibc-runner-termux` docs).
- Local glibc/newer-than-container setup: `docs/GLIBC_WORKAROUND.md`;
  Node ≥20 needs musl: `docs/SETUP_NODE_MUSL.md`.

### 3.6 The five walls (landscape report, Oct 10)

Everyone solving "glibc thing on Android" hits: (1) no
`/lib/ld-linux-aarch64.so.1`, (2) bionic-vs-glibc, (3) seccomp SIGSYS traps,
(4) `/proc/self/exe` + child-exec correctness, (5) no `/etc/resolv.conf`.
Field camps: **proot** (`oc-remote` — bulletproof, 1 GB rootfs),
**proroot** (zero-ptrace, `jniLibs`-shaped, closed source),
**glibc-runner/patchelf**, **musl build** (`C04-wq/opencode-termux` packages
the official `opencode-linux-arm64-musl` — no glibc at all).

**`HanSoBored/opencode-termux` is the one to steal from:** a ~4 KB
`LD_PRELOAD` shim that installs an **in-process SIGSYS handler** — emulates
`statx` via `fstatat`, reports every other trapped syscall as `-ENOSYS` by
setting x0 and stepping past `svc` in the ucontext. That is the in-process
equivalent of our entire ptrace+drain machinery, and it runs opencode for
real. Catch: glibc's first `set_robust_list` fires during rtld init, likely
before any preload constructor — hence the **hybrid fallback: brief ptrace
through startup, then `PTRACE_DETACH`** (per-thread traps afterwards covered
by the shim). That hybrid would also delete the SIGPWR-storm class (no stops
= no CONT latency) and sidestep the per-task ESRCH entirely.

---

### 3.7 Starter plugin config (r24, run-mode path)

`opencode run` loads the same config/plugins as `serve`, so the workspace's
`opencode.json` is where agent behavior gets tuned. On first run (if absent)
`OpenCodeManager.ensureWorkspaceConfig()` writes a minimal file that opts into
the three plugins we ship for the agent (each is a plain npm package opencode
resolves at load; a missing/unreachable package is non-fatal, the run still
works without it):

```json
{
  "$schema": "https://opencode.ai/config.json",
  "plugin": [
    "@dietrichgebert/ponytail@4.9.0",
    "opencode-bash-guard-plugin@1.0.5",
    "opencode-redact"
  ]
}
```

- **ponytail** — forces the laziest working solution (YAGNI / stdlib-first).
- **bash-guard** — safety rail on the agent's shell tool.
- **redact** — strips secrets from tool output before they hit the transcript.

**The agent path passes `--pure`**, so opencode loads *no* external plugins on
device. But declaring them in the starter config is **not** harmless: opencode
still runs its npm install path (arborist) at startup for the listed packages
even under `--pure` (opencode #31810/#37888). On device there is no npm cache and
the dns-shim blocks resolving `registry.npmjs.org`, so the run stalls at
`run <model> ...` with a healthy-looking log (r25 log 27; compare r23 log 23,
where the same lookup *succeeds* with network). Fix: the starter config now
lists **no** plugins — `--pure` would skip them anyway, so the list was pure
downside. Plugins stay an enhancement, not a dependency. To use them offline,
vendor them under the workspace's `.opencode/plugins/` and reference the local
paths.

### 3.8 Native harness: debug the elfloader drain loop without a device (round 27)

The sandbox this repo is developed in runs **on an Android kernel**
(`6.12.38-android16-5`, root, aarch64) but as a PID-namespaced container with
**no `/dev/kvm`** (mknod silently denied) and **no emulator package** — a full
AVD is structurally unavailable. But the host is aarch64 glibc with `gcc`, and
the **real `elfloader.c` compiles natively** against a ~25-line `jni.h` stub
(only `GetStringUTFChars`/`GetArrayLength`/`GetObjectArrayElement`/
`DeleteLocalRef`/`ReleaseStringUTFChars` are used, plus `JNIEXPORT`/`jint`/
`jstring`/`jobjectArray` typedefs). That turns the drain loop — the source of
every r8–r11 / r24 / r27 stall — into a **seconds-per-iteration native test**,
no device, no APK install, no log copy-paste.

The harness (`#include "elfloader.c"` so it links the *static* `drain_loop`,
`service_one`, `handle_fault_stop` and the process-globals — not a
reimplementation) must respect one rule to be faithful: **each spawner thread
forks its own child AND drains in that same thread** (the parent==drainer
invariant, elfloader.c:1003). Forking both children in `main` and draining in
separate pthreads makes `main` the parent and hides the bug entirely.

Result (r27/r28, scenarios against the real code):

| Scenario | Outcome |
|---|---|
| A: 1 spawn baseline | clean — child parked, drain joins after kill |
| B: 2 concurrent spawns | **both drain threads HUNG** — the r27 bug, reproduced |
| C: 2 concurrent + scoped `waitpid(-own_pgid)` | **NOT a fix** — see below |

**The scoped-wait idea is wrong** (r28, proven with a minimal repro): the
kernel does **not** report a tracee's ptrace-stops through a process-group
selection. Against a stopped tracee, `waitpid(-pgid, __WALL[|WUNTRACED])`
returns **0 events**, while `waitpid(tid)` and `waitpid(-1)` both see the stop
immediately; `waitpid(leader_pid)` sees the leader's stops but **drops the
`PTRACE_O_TRACECLONE` worker-thread stops** (they carry a fresh tid). So r28's
own "fix" (`waitpid(-sp->pgid)`) was itself a regression — the drain never
observed the child, so even scenario A hung. The drain is back to
`waitpid(-1)` (the only selection that sees the whole tracee thread group),
and because that is process-wide, only **one** drain may run at a time —
enforced by a single-spawn gate in `nativeSpawn` (a second concurrent
`nativeSpawn` waits a short grace period, then returns `-EBUSY`).

The **shared-state isolation** half of r28 stands, and is the part worth
keeping: `struct spawn` now owns the log path, exit code, SIGPWR/rate
counters and forensics counters, and `nativeCheck(pid)` finds its spawn in a
pid-keyed registry — a second spawn can no longer overwrite the first's log
path or leader-pid mid-flight.

**Ceiling**: true concurrent spawns (multi-agent) remain unsupported — that
needs per-tid tracking (a per-spawn set of tracee tids, waited individually)
and is deferred (issue #200). The app runs a single child
(`maxConcurrent = 1`; `runPrompt` stops the serve child first).

**Reusable for any future ptrace/drain bug**: the harness `#include`s the
real `elfloader.c` and asserts (1) `drain_loop` observes a tracee's stops and
returns on its exit, and (2) the single-spawn gate refuses a concurrent spawn,
then re-acquires after release. A standalone minimal repro of the kernel
`waitpid` selection semantics lives beside it. The opencode CLI itself was
also verified natively: two concurrent `opencode run` processes in the same
workspace both complete (3 JSON events each) — the collision is purely our
drain loop, not opencode.

---

## 4. Kotlin / Android app-layer pitfalls

- **Coroutines inherit the dispatcher that launched them.**
  `rememberCoroutineScope()` is Main. Any blocking call (`HttpURLConnection`,
  file read) on it → `NetworkOnMainThreadException`. Worse: `catch (_:
  Exception) {}` turned 15 s of instant failures into a bare timeout — cost a
  full round. Rules: blocking work in `withContext(Dispatchers.IO)`, and
  **never swallow without recording** — we now append
  `(last health: <error>)` to the timeout message, which named round 13's
  cause in one shot.
- **Cleartext on targetSdk ≥ 28/36:** the Network Security Config denies
  cleartext HTTP **including to 127.0.0.1** (my "loopback is exempt"
  assumption was wrong; the device is authoritative). One line:
  `android:usesCleartextTraffic="true"` (fine here — the only cleartext peer
  is our own server; provider APIs stay HTTPS).
- **Log truncation hides the answer.** `readLogSnippet` kept head-30 +
  tail-60 lines; the device's maps dump pushed exactly the diagnostics out —
  cost a round. Logs are now shipped full, exported on demand (`logDump`,
  200k tail + state header), and the Copy Logs button is visible in **every**
  state (healthy runs must be inspectable too).
- **Self-identify every build.** First log line is
  `elfloader: build rN <date> <time>`. A pasted log from an old build wasted
  one round; the stamp ends that class of confusion permanently.
- **Ktor 3.1.1:** `HttpTimeout` lives in `io.ktor.client.plugins` (not
  `...plugins.timeout`), per-request form is `timeout { requestTimeoutMillis =
  HttpTimeoutConfig.INFINITE_TIMEOUT_MS }`. OkHttp's default **10 s read
  timeout will kill an SSE stream that idles between agent steps** — the
  event subscription sets infinite. `HttpClient()` resolves the okhttp engine
  from the classpath (`ktor-client-okhttp` is a declared dependency in both
  modules).
- **Build facts:** Gradle → JDK 21 (`/opt/java/jdk-21.0.12.1+1`); the C
  harness → JDK 17 headers. `aapt` in the SDK is x86 — unusable here; verify
  manifest/packaging via `androidApp/build/intermediates/packaged_manifests/…`
  or a native aapt2 (`docs/AAPT2_AARCH64_WORKAROUND.md`). After every build:
  check the **dex strings** (`mimo-v2.6-flash-free`, `last health`, …) and
  both md5s — an APK without the new strings has been handed out before.
- `extractNativeLibs=true` (legacy packaging) is required so the loader can
  `mmap` `libld.so` from `nativeLibraryDir`. targetSdk 36 stays (user
  decision: "build this properly"; the loader never execs from the data dir).

---

## 5. opencode server API (the contract, verified by curl)

Get the authoritative spec from the running server: `GET /doc` (OpenAPI
3.1). Key facts:

- `GET /api/health` → **200 with empty body** (v1 schema lives at
  `/global/health`). **Unknown routes return the SPA HTML page with 200** —
  never trust the status code alone; check that the body is JSON. This
  single trap made the OpenAI-shaped `/api/chat*`, `/api/sessions`,
  `/api/models` calls look "fine" while returning markup.
- Real agent pipeline (round 15, SSE corrected r23; **agent path pivoted to
  `run` in r24**):
  ```
  # r24 — the agent's primary path (one-shot, no server to babysit):
  opencode run --format json --auto --dir <ws> -m <provider>/<model> "<prompt>"
    # flat JSON events on stdout, one per line, top-level snake_case `type` +
    # a `part` object; no {payload/properties} envelope. A completed tool_use
    # carries BOTH start+finish in one line. Transient in-process server
    # starts, feeds the run, and exits with it — no long-lived serve.
    # `-s <sessionID>` continues an existing session; tools auto-approve
    # (run is non-interactive). Parsed by `parseRunEvent()` in OpenCodeClient.kt.

  # serve + SSE path below — now only for the browser web UI, not the agent.
  POST /api/session               {model:{providerID:"opencode", id}} -> {data:{id}}
  POST /api/session/{id}/prompt   {prompt:{text}}                     -> {data:{admittedSeq,...}}
  GET  /api/event                 SSE global stream — filter by sessionID client-side
  ```
  **Do not use `GET /api/session/{id}/event` on device.** It sits behind the
  workspace-routing middleware that waits on the location boot, which never
  resolves (`workspaceID=undefined`, logs 21/22 — the SSE GET connects at
  TCP but the server never writes headers). The global `/api/event` is
  outside that middleware; both reference clients (oc-remote, P4OC) use it
  and filter by `properties.sessionID` client-side. Send
  `x-opencode-directory: <workspace root>` on every session request — the
  server resolves directory from `?directory=`, then that header, then cwd;
  cwd alone left `workspaceID=undefined` on device. The global stream
  wraps events as `{directory, payload:{type, properties}}` (the per-session
  form is flat `{type, data}`); `unwrapEventEnvelope` in `OpenCodeClient.kt`
  handles both. Live-only — no replay — so subscribe before prompting.
- **Termination rule (observed):** `session.next.step.ended` with
  `finish:"tool-calls"` = agent continues; `finish:"stop"` (or `"error"`/
  `"length"`) = run finished. `session.next.step.failed` carries
  `{error:{type,message}}`. Other useful types: `step.started`,
  `tool.input.started|delta|ended`, `tool.called`, `tool.success|error`,
  `text.delta`, `text.ended` (final text lands whole at `text.ended`).
  There is **no idle/completion event** — the stream just stays open.
- Models: the server ships hosted free models with `request.headers.apiKey:
  "public"` (**no auth**). Catalog status can lie: `exo-free` still lists
  `active` but upstream answers `410 ModelDeprecated`. Current choice:
  **`mimo-v2.6-flash-free`, fallback `space-bunny-free`** (both `opencode`
  provider). Failed model → fall through to the next instead of failing the
  run.
- **Free-tier mechanics:** no key, **IP-limited daily refresh**. The gate is
  real: clients outside the opencode TUI got
  `403 FreeTierError "OpenCode's free tier can only be used from within
  OpenCode"` (Sep 21, on recovered sessions; a `session.version` stamp bump
  did **not** fix it). Our path should pass — the caller *is* opencode — but
  if the provider starts 403ing, that string is what to grep.
- **Model IDs churn:** catalog renamed `mimo-v2-free` (Sep) →
  `mimo-v2.6-flash-free` (Oct), and `AgentBrain` once hardcoded
  `mimo-v2.5-free` (Sep MVP audit). Never trust a cached ID: resolve against
  `GET /api/model`, keep the fallback list, treat a vanished ID as normal.
  BYOK escape hatch if the free tier closes: Google AI Studio / OpenRouter
  free models (MiMo's own API: $0.10 / $0.30 per M in/out).
- `POST /api/session/{id}/wait` answers **503 when idle** — not a usable
  completion signal.
- Endpoints that exist: `/api/session` (list/create), `/api/session/{id}/…`,
  `/api/event`, `/api/provider`, `/api/model`, `/api/fs/*`, `/doc`, `/event`,
  `/session`, `/provider`. (No `/api/chat*`.)

---

## 6. Debugging methodology — what actually worked

1. **Forensics before fixes, one instrumented question per build.** The
   rounds that felt slow were the ones that changed two things; the rounds
   that closed cases (`drain ev`, heartbeat `state=/wchan=`, `TracerPid`,
   `GETSIGINFO errno=`) each added *only* logging and let one device run
   name the bug unambiguously. Every build ships a written **acceptance
   criterion** ("next log must show X or name Y") — the log then answers one
   question instead of ten.
2. **Ground truth beats recall.** Every deep cause was settled by reading the
   primary source, not by believing a doc or a model's memory: kernel
   `ptrace.c` / `seccomp.c`, glibc `abort.c` + local `libc.so.6` disasm,
   AOSP `SYSCALLS.TXT`/sigchain, and the server's own `/doc`. When a claim
   mattered (per-task ptrace), it was fetched and line-quoted before coding —
   the council/consult path is good for *ranking hypotheses*, and useless for
   *settling* kernel facts.
3. **Prove what your environment *cannot* test, and write it down.** Under
   proot the outer ptrace layer converts every seccomp SIGSYS stop into a
   silent `−ENOSYS` — no stop reaches a TRACEME parent — so the TRAP path
   structurally cannot be tested locally (`t_robust.c` records the probe).
   Knowing this stopped the local-repro chasing; the device has been the
   arbiter since round 4.
4. **Harness parity.** The local harness must reproduce the *architecture*
   under test (r12: it forks and drains from a worker pthread — running the
   old main()-thread harness would have proven nothing new). Local suite bar:
   A=0, AH=0, D=135, SA=109.
5. **Diff consecutive device logs line-for-line.** r6 vs r9 identical through
   the version checks then diverging to silence isolated the exact window
   (relocation / IRELATIVE resolver) in minutes.
6. **Surfaces swallow causes.** Every wasted round involved an error that
   had been reduced to a generic string (bare 15 s timeout, `catch {}`,
   truncated log, wrong signal label, old build). The fix each time: make the
   message carry the real cause (`last health: …`), stamp the build, ship the
   full log.
7. **Search the field before inventing.** The Termux/glibc survey found a
   working in-process SIGSYS shim (HanSoBored) — a known solution shape for
   this exact problem class, i.e. an exit ramp if ptrace ever stops paying.
8. **Split research from implementation across sessions.** The r12 root cause
   was nailed by a *second, research-only* session ("Debugging app issues from
   live logs", Oct 10): it read logs + kernel source, changed **zero** source
   files, and wrote `logs/REPORT-r12-ptrace-thread-ownership.md` with
   line-numbered edits for the implementer — who never had to re-derive the
   evidence chain. Copy the pattern: forensics session → written hand-off →
   implementer session.

---

## 7. This container's quirks (AndroidIDE / proot / aarch64)

- No `adb`, no install — the user installs via m4coding IDE (mirror path
  `app/build/outputs/apk/debug/`) and pastes in-app logs into `logs/N.txt`.
- Everything runs under **proot** (`TracerPid` = proot's `lib_proot.so`),
  which both shadows seccomp-TRAP testing *and* wedges local multi-threaded
  ptrace repros (test binaries hang). Local sandbox = smoke tests only.
- **Python's HTTP stack segfaults silently** (`urllib`, `http.client`, even a
  minimal read loop) — sockets and `curl` are fine. Use curl for all HTTP.
- The bash tool refuses commands starting with `grep`/`find`/`cat`/`sed`/
  `cd` — prefix with a `VAR=…;` or use the dedicated tools.
- Comment-opener glitch when editing: an oldString matching a ` * …` line
  must include the `/*` opener or the edit mis-targets.
- JDKs: 17 = harness/gcc, 21 = Gradle. glibc 2.27 base →
  `docs/GLIBC_WORKAROUND.md`; Node ≥20 → `docs/SETUP_NODE_MUSL.md`; x86
  `aapt`/`aapt2` → `docs/AAPT2_AARCH64_WORKAROUND.md`; IDE saves to the
  wrong module → `docs/APK_SAVE_IDE_MODULE_BUG.md`; config-cache/isolated
  projects stay off → `ANDROIDIDE_BUILD_NOTES.md`.
- **The host opencode CLI has per-version isolated HOMEs:**
  `/opencode-home/<version>/`. Upgrading 1.17.8 → 1.18.31 left every DB,
  `auth.json`, plugin, and config behind (nothing deleted — just invisible).
  Migration = `sqlite3 ATTACH` old DB + `INSERT OR IGNORE` **per project, row
  by row — never copy a whole `opencode.db` over the new one**; sidecars
  (`storage/session_diff`, `tool-output`) with `cp -n`; plugins/config copied
  by hand (the council MCP had to be reinstalled into 1.18.31 on Oct 8).
  Session rows carry a `version` stamp the free-tier gate may read (§5).
- **Local MCP server pitfalls (council build, Sep 8):** opencode speaks
  **newline-delimited JSON**, not `Content-Length` framing — support both;
  never `process.exit()` on stdin `end` while an async API call is in flight
  (pending-promise counter); always use the **absolute** node path — a
  minimal `PATH` resolves `node` to `/usr/bin/node` **v8.10 (2018)**, which
  can't even parse `catch {}`.
- **Running a locally-built ptrace/proof binary crashes the tool sandbox**
  (Oct 10: five attempts — `timeout`, pipe, background, `setsid` — all torn
  down; `ptrace_parent_proof.c` compiles but *never ran* here). One root
  cause is generalizable: a forked child **inherits the tool harness's stdout
  pipe**, so the tool never sees EOF and hangs until the harness kills it.
  Harness children must redirect stdout/stderr to `/dev/null` (+ 
  `PR_SET_PDEATHSIG`). Otherwise the device is the only arbiter of ptrace
  behavior — proot shadows it locally anyway (§6, item 3).

---

## 8. Open issues / next likely walls

1. **DNS (most likely next failure):** glibc child has no
   `/etc/resolv.conf` → provider calls to `opencode.ai` will fail on device
   even though health (loopback) passes. Now *visible* in the UI (the run
   reports the model-failure message instead of hanging). Fix shape: preload
   shim or resolv.conf drop-in (§3.5).
2. **`ServeError`** from opencode itself — seen once (Oct 8), never since;
   root cause unknown.
3. ~~**Permission prompts**~~ — **fixed in r21** (serve path); **moot on the
   agent path in r24**: `opencode run` runs non-interactively and auto-approves
   tools via `--auto`, so permission/question events never reach the app. The
   r21 auto-reply (`replyPermission` once / `questionReject`) is kept in
   `OpenCodeClient` for the serve/web-UI path only. Test:
   `OpenCodeClientEventTest` (parse both v1/v2 shapes).
4. ~~**Log growth**~~ — **fixed in loader r20**: the per-stop fault line +
   GETSIGINFO echo (~150 B each, ~1 KB/s under seccomp SIGSYS churn) now
   print the first 4 seccomp stops in full, then only every 64th as a
   heartbeat; non-seccomp (real) faults always print. The ENOSYS register
   fix-up runs on every stop — only the log writes are gated. LD_DEBUG was
   already dropped at r18.
5. **Fallback options if ptrace ever has to go** (ponytail order): (b)
   SIGSYS-shim + `PTRACE_DETACH` hybrid (§3.6), (c) official **musl** build
   (musl doesn't use `set_robust_list`/rseq → possibly zero traps).
6. **Dead code now:** after the r24 pivot the serve-path client methods
   (`createSession`/`sendPrompt`/`sessionEvents`/`replyPermission`/
   `questionReject`, `parseSessionEventJson`) have **no production caller** —
   only tests reference them (kept as the not-yet-wired web-UI client). Also
   unreferenced: the OpenAI-shaped methods (`sendChat`/`streamChat`/
   `listSessions`/`listModels`), `MockOpenCodeClient`, `MultiAgentOrchestrator`,
   and `McpHost` wiring — delete candidates when someone next touches that
   file.
7. **GitHub release dependency:** install downloads the binary + glibc libs
   from `github.com/Aderon3D/AgentCode/releases/download/opencode-binary`
   (`OpenCodeManager.kt:18`). If that release or its assets disappear, fresh
   installs break (best case: a cryptic "Not in GZIP format"). Mirror or
   bundle the assets when convenient.

---

## 9. File map

| Path | What |
|---|---|
| `agent-core/src/androidMain/c/elfloader.c` | the loader + drain + ptrace machinery (r28 shape: `spawn_server_thread`/`spawn_server_body`, `service_one` on `waitpid(-1)`, `handle_fault_stop`, `child_main` HOME/env injection, per-spawn `struct spawn` + pid-keyed registry, single-spawn gate, build stamp) |
| `scripts/build-elfloader.sh` | NDK build → `androidApp/src/main/jniLibs/arm64-v8a/libelfloader.so` |
| `agent-core/.../opencode/OpenCodeManager.kt` | install/start/stop, `waitForServer` (IO dispatcher + `last health`), `logDump()`, **`runPrompt()`** (r24: spawn `run --format json` + offset-tail) and `ensureWorkspaceConfig()` (starter plugin `opencode.json`) |
| `agent-core/.../opencode/PlatformOps.android.kt` | `httpGet`, `spawnLdLinux`, `checkChild` |
| `provider-subsystem/.../opencode/OpenCodeClient.kt` | health + serve-path session API (`createSession`/`sendPrompt`/`sessionEvents`, `SessionEvent`); **`parseRunEvent()`** (r24: flat `run` events → `SessionEvent`) |
| `provider-subsystem/.../opencode/AgentBrain.kt` | drives one prompt per model, maps events → `BrainEvent`; models in `AgentConfig` |
| `app-ui/.../ui/AgentViewModel.kt` / `AgentPanel.kt` | state, `fetchLog()`, Copy Logs |
| `androidApp/src/main/AndroidManifest.xml` | `usesCleartextTraffic="true"` |
| `logs/` | device evidence per round (`7.txt`–`13.txt`, `chat-paste-*.txt`, r12 report) |
| `thoughts/2026-10-05-opencode-elfloader-issues.md` | full round-by-round record with exact markers/md5s |
| opencode chat DBs | current `/opencode-home/1.18.31/…/opencode.db` — the complete main session (Oct 5 → 10) + the Oct 10 r12 research session; old `/opencode-home/1.17.8/…/opencode.db` — pre-Oct-5 project history (install trace, MVP audit, free-model research, council build) |
