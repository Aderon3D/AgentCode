# In-flight issues: OpenCode native ELF loader (branch `feat/android-mvp-improvements`)

Status: **resolved 2026-10-05**. Original findings preserved below the log.

## Resolution log (2026-10-05)

Reproduced the spawn path outside the app with a C harness built from
`elfloader.c` (artifacts under the app cache dir `ldtest/`), fixed what it
surfaced, and built/inspected the APK.

1. **`enter_loader` register bug (new, critical).** The naked-asm jump
   hardcoded `x0`/`x1`. GCC treats basic asm as not touching registers, so when
   it inlines the static `enter_loader` at `-O1+`, the jump target is whatever
   the allocator left in `x1` → SIGILL inside our own text. Fixed: `noinline`
   + explicit `"r"(sp)` / `"r"(entry)` operands (`elfloader.c`). The old shipped
   `.so` happened to keep the function out-of-line (worked), but the pattern was
   compiler-version-dependent. New `.so` built with NDK clang `-O2`, zero
   warnings.
2. **Verified end-to-end on this device's kernel**: harness → custom loader →
   glibc 2.39 → `opencode serve` → `curl /api/health` → `{"healthy":true}`.
   Short-lived `--version` smoke prints `1.18.27` through the same path.
3. **Rebuild reproducibility**: `scripts/build-elfloader.sh` (NDK clang, reads
   `ndk.dir` from `local.properties`). Ran it; the rebuilt `libelfloader.so` is
   in `jniLibs` and packaged in the debug APK (verified with unzip/aapt2).
4. **Log growth**: child opened `opencode.log` with `O_APPEND`; `LD_DEBUG=files`
   output accumulates forever across launches. Now `O_TRUNC` — one fresh log
   per spawn (also makes `readLogSnippet` show *this* run's failure).
5. **Zombie child**: `stop()` killed but never reaped. Now calls
   `PlatformOps.checkChild(pid)` after kill.
6. **Sentinel write**: `fileSystem.write(...)` result was dropped; a failed
   write meant a silent 62 MB re-download loop. Now `.getOrThrow()`.
7. **targetSdk 36 confirmed intentional** — old session (Sep 5–6, session
   `ses_fbce0ed28ffezuyoKXurFwcbSo`): user instructed *"revert the 28
   workaround change, we build this properly"*. The new loader only `mmap`s
   `libld.so` (read-only), it never `exec`s from the data dir, so 28 is no
   longer needed. Keep 36. (Exact-alarm path already guards with
   `canScheduleExactAlarms()`; `foregroundServiceType="dataSync"` declared.)
8. **APK inspected**: `com.agent.code`, targetSdk 36, `extractNativeLibs=true`
   (legacy packaging → readable `nativeLibraryDir` for the mmap loader),
   `libelfloader.so` + `libld.so` present (`libld.so` md5 matches
   `/opt/glibc-noble/ld-linux-aarch64.so.1`, glibc 2.39 — loader and libc
   versions match).

Still needs a **live device test by the user** (no device attached here):
fresh install → install progress completes once → server starts → restart app
→ install is skipped.

---

## Original findings (for context)

Observed while reviewing the uncommitted work that replaces the shell-wrapper
`opencode` launch with a native ELF spawner (`elfloader.c` + `ElfLoader.kt` +
`PlatformOps.spawnLdLinux`).

### 1. `elfloader.c` not wired into Gradle

- No `externalNativeBuild` / CMake / Android.mk anywhere in the repo.
- `agent-core/src/androidMain/c/elfloader.c` is built externally and the
  resulting `.so` is committed by hand → drift risk between source and binary.
- Resolution: documented build script (`scripts/build-elfloader.sh`) instead of
  Gradle wiring; `ANDROIDIDE_BUILD_NOTES.md` untouched. Add CMake wiring only
  if the script ever drifts again.

### 2. `.loader-v2` sentinel mismatch → reinstall every launch

- Install-skip check looks for `.loader-v2`; install path wrote `.loader-v1`
  → 62 MB re-download on every app start (matches the reported "couldn't
  install opencode in the live build").
- Resolution: both sides now `.loader-v2`; write result propagated.

### 3. `targetSdk 28 → 36` reverts a deliberate fix

- HEAD commit `fef5c8f` had lowered targetSdk so the app could exec from its
  data dir; the uncommitted diff bumped it back to 36.
- Resolution: intentional per user instruction ("build this properly"); the
  loader no longer execs from the data dir. See resolution log item 7.

## Device debug round — diagnostics + kernel struct fix (Oct 6)

Device still dies with raw signal 11 during rtld relocation phase (after
`libm.so.6 generating link map`), no `CRASH` line, and the raw-syscall
readback reported `handler GONE`. This round:

1. **Kernel struct layout fix** — bionic's userland `struct sigaction` is
   `{handler, flags, mask}` (24 B) but raw `rt_sigaction` passes
   `{handler, flags, restorer, mask}` (32 B): registration read 8 bytes of
   adjacent BSS as the mask (possible garbage `SIGSEGV` bit) and readback
   wrote past the stack object. New explicit `struct ksa` is used for all
   raw calls; mask is explicitly 0. (glibc's user struct matches the
   kernel's — that's why local harness always said `live`.)
2. **Dual registration** — bionic `sigaction()` first (records handler in
   libsigchain's chain table), raw `rt_sigaction` last (kernel slot holds
   `on_crash` directly, bypassing ART trampoline).
3. **Readback diagnostics** — logs syscall return, actual handler, expected
   handler (previously the return was ignored, so a failed readback looked
   like a vanished handler).
4. **`LD_DEBUG=all`** (was `files`) — log tail will show the exact last rtld
   operation before death instead of silence during relocation.

Local regression green: `--version` → 1.18.27, serve → `{"healthy":true}`
(with simulated fake-dalvik eviction), corrupted-libm → `CRASH sig=7`.

Research notes: opencode-termux documents Bun's inline `statx` hitting
Android seccomp as SIGSYS (exit 159) on Android ≤11 — ours is SIGSEGV 11,
modern device, likely different; proroot runs the same userland-exec model
but from Termux shell (no ART/sigchain/dalvik heap); kernel ≥5.15 can coerce
execve's PT_INTERP to `/system/bin/linker64` (irrelevant to us — we never
execve, we jump directly).

## Fault-PC capture round — PTRACE_TRACEME (Oct 6)

After the struct fix the device handler is `live` and **delivery works**:
the pre-jump self-test (`*(int*)0x10 = 0`) prints
`handler delivery ok (self-test)`. The child still dies silently later:
`adb logcat | grep "Fatal signal"` is empty (debuggerd never runs), and
with `LD_DEBUG=all` the log stops between the last
`checking for version` line (end of `_dl_check_all_versions`) and
`Initial object scopes` — i.e. death is inside `init_tls()` /
`security_init()` (glibc 2.39 rtld.c:2040-2045). `rtld.c` has zero
signal-related calls, and our .so has no stack canaries
(`readelf -sW | grep stack_chk` → empty), so no known mechanism explains
the silent post-jump death. The faulting PC is now the only useful datum.

Instrumentation (one-shot, log is the deliverable):

1. Child calls `ptrace(PTRACE_TRACEME)` immediately before `enter_loader`.
2. On the faulting signal the kernel *stops* the child instead of killing
   it; `nativeCheck` sees `WIFSTOPPED`, reads `PTRACE_GETSIGINFO` +
   `PTRACE_GETREGSET(NT_PRSTATUS)`, appends
   `fault stop sig= code= addr= pc=` to the child's log, then
   `PTRACE_DETACH` with sig=0 (signal discarded).
3. The child refaults untraced → normal handler delivery → `CRASH` line
   if delivery works, silent death if not. Either way the log has the PC.
4. Child's log fd opened `O_APPEND` so the parent's line can't be
   clobbered by the child's stale offset.

Kotlin needs no change (`WIFSTOPPED` → detach → return 0 = still
running); it polls `checkChild` every 200-500 ms. Local harness detaches
stops itself. Local regression green: `--version` → `1.18.27`, serve →
`{"healthy":true}` (needs ~8 s with `LD_DEBUG=all`), corrupted-libm →
traceme → detach → refault → `CRASH sig=7`.

## SIGSYS fix round — handler + fault-stop fixup (Oct 7)

Root cause was already known (zygote RET_TRAP on `set_robust_list` 99 /
`rseq` 293 in the rtld window); this round lands the actual fix and
proves everything that can be proven locally:

1. **In-child SIGSYS handler** — `on_crash` checks `SIGSYS` first:
   logs `elfloader: SIGSYS trap syscall=<n> pc=<hex>`, writes
   `-ENOSYS` into `uc_mcontext.regs[0]`, returns. glibc tolerates
   ENOSYS on both 99 and 293 (verified: boot reaches `1.18.27` while
   those syscalls return ENOSYS). `arm_handlers` registers SIGSYS for
   both bionic and kernel paths.
2. **Tracer-side fixup** — `handle_fault_stop(pid, logfd)` (called from
   `nativeCheck`'s `WIFSTOPPED` branch): GETSIGINFO + GETREGSET, logs
   `fault stop sig= code= addr= pc= syscall= x8=`, parses
   `/proc/<pid>/maps` for the pc's `pc region <line>`, reads
   `Seccomp*` from `/proc/<pid>/status`, and only when
   `si_code == SYS_SECCOMP` sets `regs.regs[0] = -ENOSYS` before
   `PTRACE_DETACH` (0). Non-seccomp stops (real faults) just get the
   forensics and detach.
3. `ELFLOADER_NOTRACE=1` env skips `PTRACE_TRACEME` (debug escape).

**proot discovery (why the local TRAP path can't be tested here):**
the AndroidIDE container runs everything under proot
(`TracerPid: 10112`, aarch64 `lib_proot.so`), which ptraces the whole
process tree and converts *every* seccomp-generated SIGSYS stop into a
silent `x0 = -ENOSYS` — for its own traps **and for ours**. Evidence:
`RET_TRAP` filter on getpid(172) → child sees `ret=-38 errno=38`, no
signal, no stop reaches a `PTRACE_TRACEME` parent (probe8), while
`RET_KILL_PROCESS` on the same nr → child dies SIGSYS 31 (kill is not
convertible); a plain `raise(SIGSYS)` still delivers normally
(`si_code=-6`), so only seccomp SIGSYS is eaten. This also retro-explains
the earlier "older filter wins for 99/293" mystery — it was proot's
conversion all along, not filter order. Consequence: the
`SIGSYS trap syscall=` and `fault stop ... syscall=99 code=1` lines can
only be confirmed on the real device (round-3 device log already showed
`sig=31 code=1` = genuine SYS_SECCOMP there). Bonus artifact: blocking
getpid locally makes glibc's `LD_DEBUG` printer assert
`pid >= 0` (it gets -38) and `_exit(127)` — device allows getpid, so
irrelevant to the fix.

**Local regression, current code:**

- Test A unfiltered: `traceme ok` → `1.18.27`, exit 0.
- Test B `HARNESS_SECCOMP=1` (ENOSYS window): `1.18.27`, exit 0 —
  glibc ENOSYS tolerance on 99/293 intact.
- Test D truncated-libm: first stop was a **real SIGBUS reaching the
  harness tracer**, so `handle_fault_stop` ran end-to-end for the first
  time — `fault stop sig=7 code=2 addr=… pc=… syscall=-1 x8=0x2`,
  `pc region … r-xp … libld.so`, `Seccomp: 2`, then detach → refault →
  in-child `CRASH sig=7` → `_exit(135)`. All new logging verified.
- Test C (TRAP path): impossible under proot, see above.

Build: `libelfloader.so` source 26600 B md5 `1629af55…`; APK
`androidApp-debug.apk` md5 `ba999e47…`, packaged so 23568 B md5
`cff2ceb4…` = `llvm-strip --strip-unneeded` of source (verified);
`libld.so` glibc rtld asset unchanged md5 `1388a58b…`.

Expected device log after install: either boot success (handler/fixup
returns -ENOSYS for 99, serve comes up), or for diagnosis:
`fault stop sig=31 code=1 … syscall=99` / `pc region` / `Seccomp: 2`
and/or `SIGSYS trap syscall=293`.

## SIGSYS round 2 — device death at worker threads + stay-traced fix (Oct 8)

**Device test (previous APK):** `Server failed to start: OpenCode exited
during startup (signal 31)`. Log: full elfloader boot (handlers armed,
window, eviction, altstack, spawner, jump, maps) then LD_DEBUG deep into
the Bun runtime — lazy bindings, pthread_create, pthread_detach — then
death. No `fault stop` / `SIGSYS trap` line in the visible log → the
process was **untraced at death** (our designed first-stop DETACH) and the
SIGSYS disposition was no longer ours (some runtime resetter). Death lands
at worker-thread creation (per-thread set_robust_list/rseq).

**Seccomp precedence (kernel docs):** filters compose by ACTION precedence
(KILL > TRAP > ERRNO > USER_NOTIF > LOG > ALLOW), *not* install order —
a task-level RET_ERRNO(ENOSYS) shim for 99/293 cannot preempt zygote's
RET_TRAP. Shim idea dead.

**Kernel seccomp.c (v6.12):** RET_TRAP → `syscall_rollback` +
`force_sig_seccomp(…, false)` (catchable, syscall returns -1);
RET_KILL_* → if `RET_KILL_THREAD && live>1` → **`do_exit(SIGSYS)`
(uncatchable by ptrace)**. Round-3 stop (`sig=31 code=1`) proved at least
one syscall is TRAP-style on device.

**Fix (stay-traced):**

1. `handle_fault_stop` SYS_SECCOMP branch: `SETREGSET x0=-ENOSYS` +
   `PTRACE_CONT` (stay attached) instead of DETACH — the tracer
   intercepts every later SIGSYS *before* disposition dispatch, so the
   unknown resetter is irrelevant. Non-seccomp faults keep DETACH
   (proven refault → handler → CRASH path).
2. `child_main` readback: added `elfloader: sigsys ret=… handler=…
   live/GONE` next to the SIGSEGV readback — identifies a resetter on
   the next device run if a stop ever races ahead of us.

**Local regressions (both green after rebuild):**

- Test A unfiltered: `1.18.27`, exit 0; both readbacks `live`.
- Test D truncated libm: `fault stop sig=7 … x8=0x2` → handled →
  `CRASH sig=7` → exit 135 (DETACH path intact).

Build: `libelfloader.so` 27288 B md5 `eac112f4…`; APK md5 `a1d5c0db…`;
APK so = llvm-strip of source (`a16da092…`); `libld.so` source unchanged
`1388a58b…` (APK copy `0002fe15…` = AGP's llvm-strip, same as always).

**Open:** if device uses RET_KILL_THREAD&&live>1 anywhere → silent
worker-thread death, no forensics (ptrace can't intercept do_exit).

Expected device log: `fault stop sig=31 code=1 … syscall=99/293` +
`pc region` + `Seccomp: 2` then boot continues; `sigsys … live/GONE`
tells whether a resetter exists.

## SIGSYS round 3 — full-log device result + TRACECLONE fix (Oct 8)

**Log-copy fix:** `readLogSnippet` kept only head30+tail60 (`…` between) —
device's longer maps dump pushed exactly the diagnostics out. Now ships the
full log; status header capped `maxLines=3`. APK `ee9c816f…`.

**Device (stay-traced APK, full log — first complete picture):**
- New build confirmed: `sigsys … live`, `traceme ok`, self-test ok.
- **Stay-traced fix worked on the leader**: `fault stop sig=31 code=1
  syscall=99 x8=0x63` + `syscall=293 x8=0x125`, both pc in `libld.so`
  (glibc rtld), forensics + fixup + CONT → rtld completed → full program
  init → Bun runtime starting (setvbuf, thread pool, pthread_create/detach
  bindings). Furthest boot yet.
- **Death**: signal 31 with **zero further stops and zero
  `SIGSYS trap` handler lines** → killer ran on a thread the tracer never
  reaped.

**Root cause (exact):** `nativeCheck` did `waitpid(leader, WNOHANG)` with
**no `PTRACE_SETOPTIONS`** → without `PTRACE_O_TRACECLONE` new threads are
not traced → a worker's `set_robust_list`(99) trap bypasses the tracer and
hits disposition directly (reset to DFL by the runtime — no handler line
even though readback said `live` at spawn) → SIGSYS DFL → whole process
dies. Leader was fine because it *was* traced.

**Fix:**
1. `nativeCheck`: `PTRACE_SETOPTIONS(TRACECLONE | EXITKILL)` every call;
   drain loop `waitpid(-1, __WALL|WNOHANG)` (all tids, up to 1000 stops);
   clone/event stops (`WSTOPSIG==SIGTRAP && status>>16`) → CONT directly;
   other stops → `handle_fault_stop(t)`; worker exit statuses ignored
   (only leader's counts). `ponytail:` note on waitpid(-1) reaping any app
   child (others are sync-reaped by ProcessRunner).
2. `handle_fault_stop`: non-seccomp stops now `PTRACE_CONT(sig)` (re-deliver
   to child's own handler, keep tracing) instead of DETACH.
3. Kotlin `waitForServer` poll 500ms → 100ms (thread storms queue behind it).

**Local:** Test A `1.18.27` exit 0 (readbacks live); Test D SIGBUS →
forensics → re-delivery → `CRASH sig=7` → 135. `.so` 27432 B; APK
`5058da12…`; packaged so = llvm-strip of source (`caf57e84…`).

**Open:** if a zygote `RET_KILL_*` syscall (not TRAP) kills later, ptrace
cannot intercept (`do_group_exit`, no stop) — then need `adb logcat -b
crash` / tombstone to name the syscall.

## SIGSYS round 4 — Bun boots! → uv_os_homedir ENOENT + SIGSTOP storm (Oct 8)

**Device result (round-3 APK `5058da12…`, full log):** result changed from
`signal 31` to `exit fail` (clean nonzero exit).
- TRACECLONE fix **worked**: worker-thread stops reaped (`fault stop
  sig=31 … syscall=99` now from `libc.so.6`), full rtld → `transferring
  control` → lazy bindings → thread pool → **`Bun v1.3.14 (Linux arm64)`
  printed** — the runtime actually ran the bundled JS
  (`/$bunfs/root/chunk-*.js`).
- **Death (real app error, no longer seccomp):**
  `ENOENT: no such file or directory, uv_os_homedir … errno -2` → Bun
  exits 1 → `childStatus=2` → "exit fail".
- Also visible: `fault stop sig=19` storm at one pc — auto-attach SIGSTOP
  of each new clone was **re-delivered** by the non-seccomp CONT branch →
  thread re-stops → loop (worker threads froze; Bun died before needing
  them).

**Fixes:**
1. `child_main`: if no `HOME=` in env, append `HOME=<dirname of binary>`
   (Android app processes have no HOME and glibc getpwuid has no passwd
   entry for the app uid → uv_os_homedir returns ENOENT → Bun dies at
   startup). Install dir exists, is writable, state lands next to the log.
2. `handle_fault_stop`: early-out on `si_signo == SIGSTOP` → `CONT(0)`
   (swallow tracer's own auto-attach stop; never re-inject).

**Local:** Test A `1.18.27` exit 0 (both with and without HOME in env —
injection path exercised via `env -u HOME`); Test D SIGBUS → forensics →
re-delivery → `CRASH sig=7` → 135. Note: `HARNESS_SECCOMP=1` (blocks
getpid) makes rtld assert `pid >= 0` (fixup returns ENOSYS) — that filter
is only for delivery experiments, Test A runs plain. `.so` 27896 B
`fd97f5e7…`; APK `bd9839ff…`; packaged so 24792 B contains `HOME=` (AGP
strip ≠ plain llvm-strip, verified by string presence).

**Open:** if Bun now boots past homedir, next candidates: TMPDIR
(Android has no writable /tmp), SIGSTOP swallow edge cases, first real
server health check.

## SIGSYS round 5 — SIGABRT storm, sender forensics + breaker (Oct 8)

**Device results (round-4 APK `bd9839ff…`):** both round-4 fixes confirmed
(no uv_os_homedir death, no sig=19 storm); full rtld → Bun boot →
**`opencode server listening on http://127.0.0.1:4096`** — the server
binds. Run 1: health check still times out at 15000ms. Run 2:
`Error: Unexpected error / ServeError` → "exit fail". Both runs show a
**SIGABRT storm**: dozens of `fault stop sig=30 code=-6` (SI_TKILL) at two
libc PCs — futex svc (offset 0x81ebc, internal futex-wait before
`__lll_lock_wait_private`) and sigsuspend svc (0x3ce48, x8=0x85 ✓).
Receivers are ASLEEP in both cases ⇒ async arrival ⇒ some thread
tkill-loops SIGABRT at them. No `CRASH` line ever: Bun/opencode's own
`sigaction` (lazy bound after our arm_handlers) replaced SIGABRT's
disposition. Each stop is re-delivered via `CONT(pid,0,si.si_signo)` and
re-raises ⇒ receivers never progress ⇒ event-loop thread starves ⇒
health timeout / ServeError.

**Ground truth:** glibc abort() = raise#1 (would stop at raise pc — never
seen), block-all, DFL, raise#2, unblock ⇒ at most ~2 stops at
raise/sigprocmask pcs (verified against glibc master abort.c + local
libc.so.6 disasm: abort@0x27d0c, raise@0x3cb20 = pthread_self+pthread_kill,
sigsuspend@0x3cdf0 svc@0x3ce48, futex-wait svc@0x81ebc). Observed stops
are NOT glibc abort flow ⇒ external looping sender; decisive missing
datum = `si_pid`. Dual libc bases in one log (`6f2d440000`+`6f55240000`)
⇒ the timed-out child was never killed (failure path never reaches
stop()) and kept appending to the same log while a retry spawned a
second.

**Fixes:**
1. Fault line logs `tid=%d spid=%ld` (spid = si_pid = tkill sender; tid≠spid
   names the loop, equal proves self-raise).
2. fp-chain backtrace `elfloader: bt …` for fatal stops only (≤8×
   PETCHDATA on x29; seccomp stops too hot) — receivers' caller chains;
   symbolize via local libc offsets + non-PIE opencode absolutes.
3. Storm breaker: 5th SIGABRT stop ⇒ log + PTRACE_KILL (normal abort ≤2
   stops; past that the loop never ends — fail fast vs 15s timeout).
   Counter reset in nativeSpawn.
4. `OpenCodeManager` catch: `kill -9` + delay 200ms + checkChild before
   reading the log snippet — timed-out spawn no longer lingers.

**Local:** A=0, `env -u HOME` A=0, D SIGBUS=135 (bt+CRASH ✓), new Test SA
(SIGABRT handler that returns + infinite raise): exactly 5 stops
(tid==spid==self, bt 5 frames) + storm line + exit 109. `.so` 28368 B;
APK `0c95fb07afdd977babb7473e0de4f77d`; packaged so 25224 B contains
`spid=`/`storm stop`.

**Open:** root cause of ServeError + identity of the SIGABRT sender (next
log's spid/bt); if spid==receiver even at the futex stop, the
delivery-stop-pc model needs rethinking.

## SIGSYS round 6 — round-5 never installed; stop re-injection, discard+cap (Oct 8)

**Device log review:** the pasted run was still the round-4 `.so` (old fault
format, no tid/spid/bt/breaker despite 40+ `sig=30` stops) — round-5 APK
`0c95fb07…` was built but not installed. New data anyway: third storm stop
site = `sched_yield+0xc` (svc@0xd5c88, x8=0x7c) → receivers parked in
futex/sigsuspend/sched_yield; `listening` prints BEFORE the storm; health
times out during it.

**Council consult:** ranked the tracer's own `PTRACE_CONT(sig)`
re-injection as the loop-sustainer for caught/re-raising handlers; Bun/opencode
definitely has JS SIGABRT listeners (exitSignals incl. SIGABRT,
signal-exit/arborist lists in binary); upstream analogues: Fedora's Bun
infinite self-signal loop, Bun crash_handler re-raise hazards (PR #32740).
glibc `abort()` ruled out structurally (DFL-then-die ⇒ ≤2 stops).
ENOSYS-on-rseq/set_robust_list ruled out as storm root: process reaches
"listening", and the zygote filter can't be bypassed (NO_NEW_PRIVS is one-way).

**Fixes (round-6):**
1. Stop 1 of a SIGABRT storm re-delivers (legit crash handler still runs);
   stops 2..60 are **discarded** (`PTRACE_CONT(pid,0,0)`) — breaks the
   re-injection loop; a genuine raiser keeps sending fresh signals and trips
   the cap-60 kill → still fails as `signal 9`, now fast (discards skip
   maps/bt, 60 stops drain in the first 100ms poll).
2. Forensics: `st=` on the fault line; `SigBlk/SigIgn/SigCgt` from
   /proc status (bit 0x20 = SIGABRT disposition caught/ignored/DFL);
   `ins pc=… val=…` (svc #0 = parked in syscall, else interrupted mid-code).
3. Perf: maps+bt moved after the seccomp early-return and behind the storm
   discard — seccomp stops cost ~4 ptrace calls now.

**Local:** A=0, `env -u HOME` A=0, D SIGBUS=135 (CRASH+bt ✓), SA=109 @
exactly 61 stops with discards at st=2/18/34/50 + `storm stop>60` line.
`.so` 28768 B; APK `27468c4e5c21ffa2fba1759abb0e9bbf`; packaged so
`36265e9b94b7648078c60c9aa4c12a09` verified to contain all new markers.

**Expected device outcomes:** (a) storm stops after st=2 and serve goes
healthy ⇒ re-injection was the loop; (b) `discard SIGABRT st=…` then
`storm stop>60, killing child` ⇒ genuine raiser — spid/bt name it; (c) no
SIGABRT stops at all (this was an old-APK artifact).

## SIGSYS round 7 — storm was SIGPWR (30), not SIGABRT; build stamps (Oct 9)

**THE BUG:** every "SIGABRT storm" analysis since round 4 was mislabeled. The
device logs say `sig=30` — on glibc/bionic that's **SIGPWR, not SIGABRT (6)**.
The round-5 breaker and round-6 discard both keyed on `SIGABRT`, so neither
could ever fire on device (hence `st=0`, no discard lines, no storm-kill, no
bt in the round-6 run — while maps/ins/SigBlk printed fine).

**The actual loop (from round-6 log via db):** `spid=2470` (child main
thread) tkill()s **SIGPWR** round-robin at workers tid 2609–2637 parked in
futex svc / sigsuspend svc / sched_yield svc (85/88 receiver pcs sit on an
`svc #0` word). SigCgt bit 29 set ⇒ SIGPWR is caught by a returning handler.
This is the known Bun "infinite self-signal loop" family (upstream Fedora
issue: Bun enters an endless SIGPWR tkill loop when its scheduler can't make
progress). Under our tracer each delivery-stop parks a receiver up to a full
checkChild drain (~100 ms), so the wake-loop is tracer-starved — the server
still boots through it ("listening" printed mid-storm) but the health check
starves and times out at 15 s.

**Fixes (round-7):**
1. **Build stamp** — `elfloader: build r7 <__DATE__ __TIME__>` is the first
   log line of every spawn (ELFLOADER_BUILD macro). Every pasted log now
   self-identifies its build; bump `ELFLOADER_ROUND` when shipping.
2. **SIGPWR fast path** in `handle_fault_stop`: stops 1–3 keep full
   forensics (fault line + Sig* + maps + ins + bt), stops 4+ are fault-line +
   **immediate re-delivery** — no maps/status/ins, so receivers resume in
   microseconds and the wake-loop converges like an untraced run.
3. **Cap** — >500 SIGPWR stops ⇒ log + PTRACE_KILL (fast `signal 9` failure
   instead of a 15 s hang) for a non-converging loop.
4. bt gate extended to SIGPWR so receiver callers are still captured.

**Local:** A=0, `env -u HOME` A=0, D SIGBUS=135 (CRASH+bt ✓), SIGPWR-loop
test (`t_sigabrt.c` repurposed to raise(SIGPWR)): exactly 501 stops,
fast-path logs at st=4/68/…, cap-kill, exit 109. `.so` 28912 B; APK
`e30ae1e49347a6378c9d8f4508482c5b`; packaged so verified to contain
`build r7`, `SIGPWR fast path`, `storm stop>500`.

**Open:** if health still fails with the fast path, the SIGPWR loop is not
tracer-amplified but a genuinely wedged Bun scheduler (upstream bug) —
next suspect would be our ENOSYS seccomp fixups for
set_robust_list/rseq wedging Bun's workers (glibc reaches "listening" so
startup survives them; runtime progress is the open question).

## SIGSYS round 8 — THE root cause: 100ms poll drain vs dedicated drain thread (Oct 9)

Round-7 fast path shipped (APK `e30ae1e…`) and the device log (`logs/1.txt`,
self-identified `build r7` — the stamp worked) **still health-timed out**:
92 SIGPWR stops ran to log end, "listening" printed mid-storm, no convergence.
Forensics still printed at st=90+ (fast path was placed *after* the status
block — fixed in r8 by moving it above).

**The actual root cause, finally:** `nativeCheck` (called from Kotlin's
`waitForServer` every 100ms via `delay(100)`) used `waitpid(…, WNOHANG)` —
between polls **every ptrace stop parks the child for up to 100ms**. The
child hits stops constantly: seccomp traps (set_robust_list/rseq per new
thread), TRACECLONE events, and every SIGPWR tkill delivery-stop. Bun's
worker-wake loop (tkill SIGPWR → worker acks) needs sub-millisecond
round-trips; it never got one, re-sent forever = the storm. This is exactly
why the same binary runs fine under the local harness (blocking waitpid in
its own loop = proot-architecture) and under proot (dedicated event loop,
µs stops) but livelocks in the app.

**Fix (r8):** a **dedicated drain thread** (`drain_loop`) spawned in
`nativeSpawn` owns `waitpid(-1, …, __WALL)` **blocking** — stops are
serviced in µs. `nativeCheck` is now a one-liner reading the atomic
`g_child_exit` the thread records on leader exit; Kotlin unchanged.
SETOPTIONS (TRACECLONE|EXITKILL) is set once on the first stop in the
thread. The SIGPWR fast path (stops 4+ re-deliver with no forensics, cap
500 → kill) stays as a safety net, now placed before any /proc read.

**Local (harness, unchanged drain):** A=0, AH=0, D=135, SA=109 with
fast-path verified (SigBlk only on stops 1-3, cap-kill at >500). `.so`
29352 B (NDK links pthread into libc); harness needs `-pthread` for its own
build now. **APK `a95242408c5466b5f751ffdab5aab141`** — first log line
`elfloader: build r8 …`.

**Also in this round:** user created `logs/` in the repo; all 14 chat-pasted
device logs extracted from the opencode db into
`logs/chat-paste-<UTC>-<msgid>.txt` (Oct 6 → Oct 9); `logs/1.txt` is the
user's own save of the r7 run. Permission question answered: INTERNET and
all needed permissions are declared in the manifest and work (server bound
127.0.0.1:4096; no runtime permission is involved in the health path).

**Prediction:** with µs-latency stops the SIGPWR storm should converge like
it does untraced — if health passes on r8, this was the whole bug. If it
still fails, the loop is genuinely Bun-side and we go after the wake-loop
itself (discard-instead-of-reinject, or an in-child seccomp ERRNO filter
to eliminate seccomp stops entirely).

## SIGSYS round 9 — r8: storm fixed, new stall diagnosed; drain forensics + backup sweep (Oct 9)

**r8 device log (`logs/8.txt`) — big win, new mystery:**
- **ZERO SIGPWR stops** (r7 had 92). The dedicated drain thread killed the
  storm completely — the 100ms-poll root cause was correct.
- **But health still failed**: the child never reached "listening". It got
  through rtld library loading + version checks, then went **silent for the
  remaining ~14s** at exactly the boot point where r7 hit its first
  `set_robust_list` seccomp trap. One `fault stop sig=0 code=0 … pc=0x0`
  line at the very end with all-zero siginfo/regs + zombie-style /proc
  read (maps/ins/bt absent) = the classic **kill-race artifact**: Kotlin's
  `kill -9` at the 15s timeout killed a task that had been **parked at an
  unserviced stop since ~1s**.
- **The contradiction**: the parked stop should have been the drain
  thread's first `waitpid` return (µs later). Instead the thread's first
  serviced event was the 16s kill-race one. So either the thread never
  started, died, or its `waitpid` went elsewhere for 15s — can't resolve
  from the log alone.

**Fix (r9), two layers:**
1. **Drain forensics** — `service_one()` logs every event
   (`elfloader: drain ev tid=… st=…`), SETOPTIONS/waitpid errors with
   errno, thread up/exit. One device run shows exactly what the thread saw
   and when.
2. **Backup WNOHANG sweep in `nativeCheck`** — Kotlin's 100ms poll now
   services pending stops itself (same `service_one`, r7-slow) if the
   thread lagged/died → **no permanent stall is possible anymore**,
   whatever is hiding in there.

`.so` 30040 B; local A=0 AH=0 D=135 SA=109; **APK
`f9b5bdf78a6fd9febcf499e56c2587ac`** (stamp `build r9`).

**Next:** read the r9 log's `drain …` lines — they name the stall
unambiguously (no thread-up line = pthread_create failed; thread-up but no
ev lines until 16s = waitpid starved; SETOPTIONS errno = options never
applied).

## SIGSYS round 10 — r9 log: child never stops, it goes silent; O_TRUNC race found (Oct 9)

**r9 device log (`logs/9.txt`) — the field thinned out decisively:**
- No `drain thread up` line, no `fault stop`, no `drain ev` — **the backup
  sweep never saw a pending stop in 15s. The child is NOT parked at a
  ptrace stop.** It runs silent.
- Diffed against the r6 boot: **r6/r9 are identical line-for-line through
  the rtld version checks (lines 201-212)**, then r6 hits the
  `set_robust_list` seccomp trap (serviced, ENOSYS, boot continues to
  bind/listen) while r9 produces **nothing more** — stuck in the silent
  window right after version checks (relocation / IRELATIVE-resolver
  territory), no fault, no CRASH, no stop.
- Missing `drain thread up` = **O_TRUNC race**: child_main opened the log
  with `O_TRUNC|O_APPEND` (elfloader.c:484); the child's open can land
  after the drain thread's startup write and erase it. The thread almost
  certainly ran in r8/r9 — we just couldn't see its line.

**Fix (r10), all forensics to close the case in ONE more device run:**
1. Truncate race fixed: parent truncates in `nativeSpawn` before fork;
   child opens `O_APPEND` only. Every parent+child line is now permanent.
2. `pthread_create` failure is now logged (`drain pthread_create FAIL`).
3. **2s heartbeat in `nativeCheck`**: leader `/proc` state letter + `wchan`
   + kernel stack (`/proc/<pid>/stack`, root) — names "traced-parked" /
   "blocked-in-syscall-X" / "running" unambiguously in the next log.

`.so` 30952 B; local A=0 AH=0 D=135 SA=109; **APK
`31c62cb8ee223f61cd8016916336f6a6`** (stamp `build r10`).

**Next:** the r10 log's `hb state=… wchan=…` + stack lines end the
parks-vs-runs question; from there either the syscall it's blocked in
(futex? something of ours) or "R" (userspace spin in rtld relocation —
then suspect the mapping/relocation environment) leads the fix.

## SIGSYS round 11 — r10 log: SETOPTIONS ESRCH from the drain thread (Oct 9)

**r10 device log (`logs/10.txt`) — the heartbeat + drain forensics paid
off, the stall is fully named:**
1. `drain thread up` — thread runs fine (the r9 "missing line" was just
   the O_TRUNC race, as suspected).
2. First stop arrives: `drain ev tid=10275 st=0x1f7f` = SIGSYS
   delivery-stop (the `set_robust_list` seccomp trap, same as r6).
3. **`drain SETOPTIONS errno=3` — PTRACE_SETOPTIONS fails with ESRCH**,
   and every later ptrace op on the task fails the same way
   (`fault stop sig=0 … pc=0x0` = failed GETSIGINFO/GETREGSET), so the
   ENOSYS fixup and CONT silently no-op.
4. Heartbeats: `hb state=t wchan=ptrace_stop` ×7 — the child sits parked
   in the delivery-stop for ~14s until `kill -9`.

So `waitpid` sees the stop (we own the tracee for waitpid) but `ptrace()`
from the **drain pthread** gets ESRCH — while the identical SETOPTIONS
call from the **JNI thread** worked every time in r6/r7.

**Fix (r11):** SETOPTIONS restored to `nativeCheck` (JNI thread, exact
r6/r7 path, on the leader pid, every poll — idempotent); the drain thread
now only CONTs (its SETOPTIONS attempt kept as best-effort, non-fatal).
Forensics added for the residual question: **TracerPid** line in the
status block, **GETSIGINFO errno** line when it fails.

`.so` 31016 B; local A=0 AH=0 D=135 SA=109 (TracerPid visible in local
logs); **APK `4615c9719c2abacb03478365c7f04e17`** (stamp `build r11`).

**Next:** if SETOPTIONS-from-JNI makes the seccomp fixup work again, boot
should proceed past the trap (like r6) WITH µs stop latency (drain
thread) — the storm should stay dead and health should be attempted for
real. If anything still fails, `TracerPid:` + `GETSIGINFO errno=` name it.

## SIGSYS round 12 — root cause confirmed in kernel: ptrace is per-TASK (Oct 10)

**r11 device log (`logs/11.txt`) closed the last door:** `GETSIGINFO
errno=3` with `TracerPid: 20409` (= the JNI/forking thread), first stop
`st=0x1f7f`, child parked 14s (`hb state=t wchan=ptrace_stop` ×7), kill
`st=0x9`. SETOPTIONS-from-JNI could not help because **every** `ptrace()`
issued from the drain thread fails: verified in upstream
`kernel/ptrace.c` — `ptrace_check_attach()` (line 269) requires
`child->ptrace && child->parent == current` else `-ESRCH`; the syscall
entry (line 1435) routes every request except TRACEME/ATTACH/SEIZE through
it; `ptrace_traceme` (line 527) links the tracee to **the task that
called fork()**. `waitpid` is process-scoped, `ptrace` is task-scoped —
that is exactly why r8-r11 saw stops but could never service them
(r6/r7 worked only because nativeSpawn+nativeCheck shared one thread).

**Fix (r12):** `spawn_server_thread` — nativeSpawn copies all strings
into a heap `spawn_req`, truncates the log, then pthread_create's a thread
that forks, sets `g_child_pid`, frees the request and enters `drain_loop`
on the SAME thread. fork + waitpid + SETOPTIONS + ENOSYS fixup + CONT now
share the parent task. nativeCheck lost its SETOPTIONS + WNOHANG sweep
(single-waiter principle — a second waiter races the drain for the same
event); heartbeat kept. `service_one` marks `opts_set` only when
SETOPTIONS succeeds (retry at next stop; the first stop is the main
thread's pre-clone pthread-init trap, so TRACECLONE lands before any
clone exists).

**Local suite on the r12-shaped harness** (harness.c now forks and drains
from a worker pthread — the same invariant; running it from main() would
test nothing new): **A=0, AH=0, D=135, SA=109** — all r11-bar values.
`.so` 31504 B (28000 B packaged, = stripped per established pattern).

Test notes: harness's seccomp filter no longer traps getpid(172) — with
`LD_DEBUG=all` unconditional in child_main, `_dl_debug_vdprintf` asserts
`pid >= 0` on -38 (ah1). Filter now traps getppid(173): the one syscall
this container does not shadow. Even so the SIGSYS→fixup branch cannot be
*proven* locally (round-4 finding stands: the container's outer layer
absorbs the seccomp event — no stop reaches the tracer, no handler log;
untraced child still observes ENOSYS) — `t_robust.c` records the probe
(r=-1 errno=38 under filter, real ppid without; child handler never
fires). Device decides, as in every round since round 3.

**APK `26149e22608d4f76c6d47e07526509a0`** (stamp `build r12 Oct 10
2026 10:47:50`, mirror synced).

**Acceptance for the r12 device run:** first log line `elfloader: build
r12 …`; no `GETSIGINFO errno=3` / no `fault stop sig=0 … pc=0x0`; the
first stop's fixup lands (`SIGSYS trap syscall=99` or `fault stop …
syscall=99`), boot proceeds past rtld version checks to `listening`,
health `{"healthy":true}` within 15s. If SETOPTIONS/fixup still ESRCH,
something else re-parented the tracee (report it with the new log).

## Round 13 — NEW problem, client-side: health check ran on the UI thread (Oct 10)

**r12 device log (`logs/12.txt`, 1980 lines) — the ptrace saga is OVER:**
`build r12`, **zero** `GETSIGINFO errno`, 18 SIGSYS stops with real
forensics (17× `syscall=99`, 1× `syscall=293` — fixups landing), 16 clone
events + 16 autostops (16 worker threads), 182 SIGPWR stops serviced via
fast path (breaker never fired), heartbeats `state=S wchan=do_epoll_wait`,
and line 1547 **`opencode server listening on http://127.0.0.1:4096`** —
then idle ~12s, kill -9 at the 15s timeout, EXITKILL reaps 17 tids,
`drain thread exit`. Server healthy, client never connected.

**Root cause (Kotlin, not the child):** `MainActivity` builds the
ViewModel with `rememberCoroutineScope()` (= Main dispatcher);
`scope.launch { manager.start(...) }` runs `waitForServer()` on the UI
thread; `HttpURLConnection.responseCode` there throws
`NetworkOnMainThreadException` on **every** attempt; `catch (_:
Exception) {}` swallows it; 15s of instant failures; kill. (Endpoint
itself verified good: same binary locally returns 200 on `/api/health`
and `{"healthy":true,"version":"1.18.27"}` on `/global/health` — the
v1 health schema lives at `/global/health` in upstream `global.ts`.)

**Fix:** `withContext(Dispatchers.IO)` around the blocking `httpGet`
(same pattern as `downloadAndExtract`), plus the last health error is
now captured into `lastError` and appended to the timeout message — a
failure mode can never hide behind a bare timeout again.

**APK `accffee6c1c0e8b31e4f07c4e3e270d8`** (mirror synced; `last health`
string verified in packaged classes10.dex; `.so` unchanged r12).

**Acceptance for the next device run:** `Running(port, pid)` state, or a
timeout message that now names the real cause `(last health: …)`.

## Round 14 — health fails on cleartext policy, named by the new error (Oct 10)

**r13 device log (`logs/13.txt`) — the `lastError` reporting paid off on
the first run:** `did not start within 15000ms … (last health:
java.io.IOException: Cleartext HTTP traffic to 127.0.0.1 not permitted)`.
So the Dispatchers.IO fix worked (no more NetworkOnMainThread — requests
are really attempted now) and the next layer is Android's Network Security
Config: targetSdk 36 denies cleartext by default, loopback included. (My
earlier "loopback is exempt" assumption was wrong — the device is
authoritative.)

**Fix (1 line, native platform knob):**
`android:usesCleartextTraffic="true"` on `<application>` — the app's only
cleartext peer is its own loopback server; provider APIs stay HTTPS.
Verified in `packaged_manifests` output (aapt is x86-only, unrunnable on
this aarch64 container).

**APK `6d978fa7d92f14c2e222427f2523ebf3`** (mirror synced).

**Acceptance for the next device run:** `/api/health` → 200 from the app
process → `Running(port, pid)`. If it still times out, the message will
name the next cause.

## Round 15 — server runs; the agent is rewired to the real session API (Oct 10)

**Confirmed working:** the app boots the server and `/api/health` now
returns 200 — the ptrace/eliloader saga (rounds 1-12) is closed.

**Two things surfaced together:** (a) the log was only reachable through
the `Error` state, so a healthy run could not be inspected; (b) typing a
goal did nothing visible. (b) was pre-known from the round-14 audit: the
client speaks OpenAI-shaped `/api/chat*`, which this server does not
serve — unknown routes return the SPA **HTML fallback with HTTP 200**, so
the SSE parser silently produced zero events and the brain looped to
`maxIterations`.

**Real API (from the server's own OpenAPI doc at `/doc`, cross-checked
with curl):**
```
POST /api/session                  {model:{providerID,id}} -> {data:{id}}
POST /api/session/{id}/prompt      {prompt:{text}}         -> {data:{id,...}}
GET  /api/session/{id}/event       SSE
```
Termination rule (observed, not guessed): `step.ended` with
`finish:"tool-calls"` = agent continues; anything else (`stop`) = run
finished. `step.failed` carries the error. Useful event types:
`step.started`, `tool.input.ended`, `tool.called`, `tool.success|error`,
`text.delta`, `text.ended`. Subscribing *after* the prompt still replays
the whole run, so no stream/prompt race.

**Models:** the server ships free hosted models with `apiKey:"public"` —
no auth needed. Default (`exo-free`) is 410 `ModelDeprecated` upstream
even though the catalog still lists it active, so the model is chosen
explicitly: **`mimo-v2.6-flash-free`, falling back to
`space-bunny-free`**. A failed model (bad id, provider 4xx/5xx, DNS
failure on device) falls through to the next instead of killing the run.

**Changes:**
- `OpenCodeClient`: `createSession`, `sendPrompt`, `sessionEvents` (SSE
  → `SessionEvent`), with an HTML-fallback guard on create and an
  infinite socket timeout on the event stream (it idles between steps;
  OkHttp's default 10s read timeout would kill healthy runs).
  Ktor 3.1.1 per-request timeout is `timeout { }` from
  `io.ktor.client.plugins`, **not** `install(HttpTimeout)` under
  `...plugins.timeout`.
- `AgentBrain.executeTask`: drives one prompt per model and maps session
  events onto the existing `BrainEvent`/UI plumbing. The hand-rolled
  OpenAI tool-call loop (and its `mcp` dependency — opencode brings its
  own tools) is gone; `McpHost` is no longer passed in.
- Log export: `OpenCodeManager.logDump()` tails the log (200k cap, state
  header) and a **Copy Logs** button is now always visible, auto-copying
  to the clipboard on any state — healthy runs included.

**APK `635c7c409ff7ab24b5dc3c90949bcb25`.**

**Env note:** python's HTTP stack segfaults silently in this container
(`urllib`, `http.client`, even a bare `read` loop) — use `curl` for HTTP
probing here. Sockets and curl are fine.

**Still open:** provider DNS on device (glibc has no `/etc/resolv.conf`;
if MiMo/Space Bunny can't resolve, the UI now shows the exact error), and
permission prompts (`/api/permission/*`) if opencode ever asks mid-run.

## Related docs

- `docs/OPENCODE_ON_ANDROID_LESSONS.md` — **read this first**: the distilled
  lessons (round history, deep Android/Linux knowledge, server API, gotchas)
  needed to make this a 5-minute job next time.
- `docs/GLIBC_WORKAROUND.md` — side-loaded glibc + loader background.
- `ANDROIDIDE_BUILD_NOTES.md` — why config cache/isolated projects stay off.
