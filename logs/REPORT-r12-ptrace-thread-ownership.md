# AgentCode — elfloader debugging report (for the next agent)

**Scope:** why `OpenCode` never starts on device, the confirmed root cause, and the
exact change to land next (r12).
**Date:** 2026-10-10
**Author of report:** research/planning pass only — **no source was modified.**
**Read this whole file before touching `elfloader.c`.**

---

## 1. TL;DR

`elfloader.c` forks the traced child on the **JNI/Kotlin thread**, but since round 8
all ptrace stop-servicing was moved to a **pthread** (`drain_loop`). Linux requires
ptrace requests to originate from the tracee's **parent task** — not just from the
tracer's process. Every ptrace call from the drain pthread therefore returns
**ESRCH (3)**: the seccomp `set_robust_list` → `-ENOSYS` fixup never lands, the child
parks in `ptrace_stop` at its first seccomp trap for ~14 s, and Kotlin's 15 s timeout
kills it.

Fix (r12): **fork the child on the thread that will own the drain loop.** One file
changes (`agent-core/src/androidMain/c/elfloader.c`). No Kotlin change.

---

## 2. Where the project stands

- `logs/` holds 6 live device logs (`7.txt` = r7, `8.txt` = r8, `9.txt` = r9,
  `10.txt` = r10, `11.txt` = r11) plus 14 chat-pasted device logs
  (`chat-paste-*.txt`). `thoughts/2026-10-05-opencode-elfloader-issues.md` is the
  11-round change log; **read it first**, it is the history of every dead end.
- Boot progress so far: rtld relocation ✅ → Bun runtime ✅ → `opencode server
  listening on http://127.0.0.1:4096` ✅ (r6/r7) — then stuck.
- Two *already-fixed* bugs are no longer suspects: `uv_os_homedir` (HOME injection,
  r4) and the SIGPWR worker-wake storm (dedicated drain thread, r8 — r8's log shows
  **zero** storm stops).
- The **only** unfixed defect is the ptrace-thread-ownership bug below. r8 is the
  proof: its drain thread fixed the storm but could not fix up a single seccomp trap.

---

## 3. Root cause — kernel-level, not a guess

`torvalds/linux kernel/ptrace.c` (master; the check is unchanged on Android's GKI):
```c
/* ptrace_check_attach() */
read_lock(&tasklist_lock);
if (child->ptrace && child->parent == current) {
        if (ignore_state || ptrace_freeze_traced(child))
                ret = 0;
}
/* ret stays -ESRCH unless the CALLING TASK is the tracee's parent */
```

and the tracer is pinned at spawn time:

```c
static int ptrace_traceme(void)          /* PTRACE_TRACEME */
{
        ...
        current->ptrace = PT_PTRACED;
        ptrace_link(current, current->real_parent);   /* -> child->parent = <forking task> */
}
```

`SYSCALL_DEFINE4(ptrace, …)` runs `ptrace_check_attach()` for **every** request
except `PTRACE_TRACEME`, `PTRACE_ATTACH`, `PTRACE_SEIZE`, and passes
`ignore_state=true` only for `PTRACE_KILL`/`PTRACE_INTERRUPT` (the `child->parent ==
current` test still applies to those).

Consequences, all observed on device:

- `PTRACE_SETOPTIONS`, `GETSIGINFO`, `GETREGSET`, `SETREGSET`, `CONT`, `KILL` from
  any thread other than the fork caller → **ESRCH**.
- `waitpid()` is thread-group scoped, so it **does** work from any app thread. That
  asymmetry is exactly what the logs show.

---

## 3.1 Local reproduction attempt (2026-10-10, this sandbox — NOT completed)

A 60-line pthread/fork/`PTRACE_TRACEME` harness was written and compiled here
(`/data/user/0/com.m4coding.ide/cache/opencode/ptrace_parent_proof.c`, builds clean with
`cc -O1 -Wall -pthread`) to print `SETOPTIONS` errno from the parent task vs. a sibling
thread. **Every execution attempt tore down the shell before output could be captured**
(`proof.out` came back empty, process never observed by `ps`) — this sandbox does not
survive multi-threaded ptrace, so no local result exists. The conclusion above rests on
the kernel source plus the device log evidence in §4, not on this attempt.

**Action for the next agent:** run that harness on the dev host (or skip it and go
straight to §8.2/§8.3 — evidence is already conclusive). Do not spend another round
trying to make the sandbox cooperate with ptrace.

---

## 4. Evidence chain (device logs)

| Log | Build | Waiting | ptrace | Result |
|-----|-------|---------|--------|--------|
| `logs/7.txt` | r7 | JNI thread (100 ms poll) | **works** | full rtld → Bun → `listening` → SIGPWR storm (92 stops) → health timeout |
| `logs/8.txt` | r8 | drain pthread | **ESRCH** | **zero** SIGPWR stops (storm fixed); log ends at line 214 with `fault stop sig=0 … pc=0x0` — same ESRCH symptom, no fixup, no boot |
| `logs/9.txt` | r9 | drain pthread | n/a | silent through rtld version checks, nothing more |
| `logs/10.txt` | r10 | drain pthread | **ESRCH** | `drain SETOPTIONS errno=3`, `pc=0x0`, parked → killed |
| `logs/11.txt` | r11 | drain pthread | **ESRCH** | `drain ev … st=0x1f7f` then `GETSIGINFO errno=3`, `TracerPid: 20409`, `hb state=t wchan=ptrace_stop` ×7, `st=0x9` (SIGKILL) |

Key r11 lines (`logs/11.txt`):

```
218: elfloader: drain ev tid=22980 st=0x1f7f      <- waitpid from pthread WORKS (SIGSYS stop)
219: elfloader: fault stop sig=0 … pc=0x0         <- GETSIGINFO/GETREGSET FAILED (regs all zero)
220: elfloader: GETSIGINFO errno=3                <- ESRCH
221: elfloader: TracerPid: 20409                  <- the app pid == the forking MAIN thread
222-226: SigBlk/SigIgn/SigCgt/Seccomp read OK     <- /proc reads work from any thread
227-233: elfloader: hb state=t wchan=ptrace_stop  <- parked at the trap for the full 14 s
234: elfloader: drain ev tid=23050 st=0x0         <- unrelated: OpenCodeManager's `kill` subprocess reaped
235: elfloader: drain ev tid=22980 st=0x9         <- leader killed by SIGKILL at the 15 s timeout
```

Why r6/r7 worked: `OpenCodeManager.start()` runs on the injected coroutine scope
(VM scope → Main dispatcher), so `nativeSpawn` and `nativeCheck` execute on the
**same main thread that called fork**. In `logs/11.txt` the app main thread is pid
`20409` (all other app threads are `22649`–`22718`, the child is `22980`) — that pid
is the *specific thread* recorded as `child->parent`. The r11 "SETOPTIONS from the
JNI thread" idea was therefore not wrong in principle, but it left the *servicing*
(GETSIGINFO/SETREGSET/CONT) on the pthread, so it could not help.

Corroboration that ptrace identity, not seccomp, is the blocker: `fault stop sig=31
code=1 … syscall=99` (set_robust_list) appears in r6/r7 and in r10/r11 alike; only
the thread issuing the fixup differs.

---

## 5. The change (r12) — `agent-core/src/androidMain/c/elfloader.c`

No Kotlin change is needed; `ElfLoader.kt` / `PlatformOps.android.kt` /
`OpenCodeManager.kt` stay as they are.

### 5.1 Bump the build stamp

```c
/* elfloader.c:40 */
#define ELFLOADER_ROUND "r12"
```

### 5.2 Spawn request (so the strings outlive the JNI call)

Add near the other statics (~elfloader.c:731):

```c
struct spawn_req {
    char loader[4160], glibc[4160], binary[4160], work[4160], log[4160];
    const char *args[LD_MAX_ARGS];
    char argbuf[LD_MAX_ARGS][512];
    int nargs;
};
```

### 5.3 `nativeSpawn` — stop forking, spawn the thread instead

Replace `pid_t pid = fork(); … pthread_create(drain_loop)` (elfloader.c:946–984) with:

```c
struct spawn_req *req = calloc(1, sizeof *req);
/* copy jLoader/jGlibc/jBinary/jWork/jLog and the jArgs strings into req */
/* (same length guards as child_main uses today: 4160 / 512) */

g_pwr_stops = 0;
g_child_pid  = -1;                       /* sentinel: not forked yet */
__atomic_store_n(&g_child_exit, 0, __ATOMIC_RELEASE);
snprintf(g_child_log, sizeof g_child_log, "%s", logp);
g_child_log_set = 1;
int tfd = open(logp, O_WRONLY | O_CREAT | O_TRUNC, 0644);   /* parent truncates, r10 fix */
if (tfd >= 0) close(tfd);

pthread_t th;
if (pthread_create(&th, NULL, spawn_server_thread, req) != 0) {
    int e = errno; free(req); return -e;
}
pthread_detach(th);
for (int i = 0; i < 500 && g_child_pid <= 0; i++) usleep(1000);   /* wait for the fork */
return (jint)g_child_pid;               /* >0 pid, or -errno / -ETIMEDOUT */
```

Notes:
- `req` is never written by the server thread, so no handoff is needed beyond
  `pthread_create`'s happens-before edge.
- The child (forked inside the server thread) copies `req`'s strings onto its own
  stack in `child_main` and never returns; the parent must not reuse `req`, hence the
  per-spawn allocation.
- Keep the existing "log truncation in the parent before creating the thread"
  (r10's O_TRUNC-race fix) — move the `open(O_TRUNC)` above, it is unchanged.

### 5.4 New `spawn_server_thread`

```c
/* This thread MUST be the one that forks: Linux requires every ptrace request to
 * come from the tracee's parent task (ptrace_check_attach: child->parent ==
 * current). r8-r11 serviced stops from a pthread and got ESRCH on every op. */
static void *spawn_server_thread(void *arg) {
    struct spawn_req *r = arg;
    pid_t pid = fork();
    if (pid == 0) {
        child_main(r->loader, r->glibc, r->binary, r->work, r->log,
                   (const char **)r->args, r->nargs);
        _exit(127);
    }
    if (pid < 0) { g_child_pid = -errno; return NULL; }
    g_child_pid = pid;

    /* TRACECLONE|EXITKILL from the parent thread. SETOPTIONS needs the tracee in
     * TASK_TRACED, i.e. at its first stop, so retry cheaply until it lands. */
    for (int i = 0; i < 2000; i++) {
        if (ptrace(PTRACE_SETOPTIONS, pid, 0,
                   (void *)(long)(PTRACE_O_TRACECLONE | PTRACE_O_EXITKILL)) == 0)
            break;
        if (errno != ESRCH) break;
        usleep(200);
    }
    drain_loop(NULL);                    /* existing blocking waitpid(-1,__WALL) loop */
    return NULL;
}
```

### 5.5 `nativeCheck` — remove the ptrace that can never work

elfloader.c:1059–1076 currently does a JNI-thread `PTRACE_SETOPTIONS` and a 1000-iteration
WNOHANG sweep. Delete both:

```c
JNIEXPORT jint JNICALL
Java_com_agent_code_opencode_ElfLoader_nativeCheck(JNIEnv *env, jobject thiz, jint pid) {
    (void)env; (void)thiz; (void)pid;
    /* NO ptrace here: the child's parent is the spawn thread (see
     * spawn_server_thread). A second waiter would also race the drain loop for
     * the same stop. Keep only the exit code + the heartbeat. */
    ... keep the hb block verbatim ...
    return __atomic_load_n(&g_child_exit, __ATOMIC_ACQUIRE);
}
```

### 5.6 `service_one` — drop the dead SETOPTIONS

elfloader.c:1004–1010 (`service_one`'s "best-effort" `PTRACE_SETOPTIONS`, commented
*"may ESRCH on device"*) is now redundant; remove it and the `opts_set` parameter.

### 5.7 Edge case to cover

If the child exits before its first stop (SETOPTIONS retries to ECHILD), record a
definitive status so Kotlin says "exit fail" instead of timing out:

```c
if (ptrace(PTRACE_SETOPTIONS, ...) != 0 && errno == ECHILD) {
    __atomic_store_n(&g_child_exit, 2, __ATOMIC_RELEASE);   /* matches childStatus("exit fail") */
    return NULL;
}
```

Place the check at the end of the retry loop (bounded: 2000 × 200 µs = 400 ms).

---

## 6. Do NOT do these (already proven dead)

- SECCOMP userland ERRNO shim for 99/293 — action precedence (TRAP > ERRNO) means
  zygote's `RET_TRAP` always wins.
- Re-arm SIGSYS in-child instead of tracing — Bun/opencode installs its own handlers
  and the disposition reverts to DFL; then a worker thread's `set_robust_list` kills
  the process. Also each new thread re-hits the trap, so it must be covered for the
  whole process lifetime.
- `PTRACE_DETACH` on fault stops / stopping the trace early — forensics and
  re-delivery both need you to stay attached.
- Changing `targetSdk` (36 is intentional) or APK packaging (`extractNativeLibs=true`
  is required for the mmap loader).

---

## 7. Risk register

| Risk | Mitigation |
|------|------------|
| `PTRACE_SEIZE` considered instead? | No: it needs a second handshake (child must not enter rtld before being seized) and would re-parent the child — strictly more code than moving the fork. |
| Child creates threads before SETOPTIONS lands | The retry loop is 200 µs vs. r6/r7's 100 ms poll; rtld's first `set_robust_list` is the first thread-creating event and it traps → the loop exits at that stop. This is the round-3 bug, now closed properly. |
| Drain thread dies silently | `hb state=… wchan=…` heartbeat still runs from `nativeCheck`; the log will show it. |
| Local harness can't reproduce (proot eats seccomp SIGSYS) | Local harness is still required for `--version`, truncated-libm (`CRASH sig=7`), and the SIGPWR loop (cap kill). Device run is the only place the seccomp path can be verified. |
| `waitpid(-1, __WALL)` reaps `ProcessRunner`'s `kill` subprocess | Pre-existing since r8, visible as `drain ev tid=23050 st=0x0` in r11. The kill has already executed by then; harmless. Do not spend time on it. |
| `nativeSpawn` called concurrently | `OpenCodeManager.start()` holds `stateMutex`; one spawn at a time. The malloc'd request also makes accidental double-use harmless. |

---

## 8. Verification plan

### 8.1 Build

```bash
cd /storage/internal_new/project/AgentCode
scripts/build-elfloader.sh                 # NDK clang, reads ndk.dir from local.properties
strings androidApp/src/main/jniLibs/arm64-v8a/libelfloader.so | grep -m1 'build r12'
./gradlew :androidApp:assembleDebug
```

Then install the APK on the device (fresh install so `.loader-v2` skips the 62 MB
download — verify the sentinel is `.loader-v2`, see r1 finding #2).

### 8.2 Local harness (recreate; not in repo)

The harness in `thoughts/…-elfloader-issues.md` lived in the app cache `ldtest/` and
used `LD_TEST_VERSION` to map `<glibc>/libc.so.6` as the "program". Minimum set to
recreate:

| Test | Setup | Expect |
|------|-------|--------|
| A | plain `--version` | prints the opencode version, exit 0; both readbacks `live` |
| AH | `env -u HOME` | prints version, exit 0 (HOME injection path) |
| D | truncated `libm.so.6` | `fault stop sig=7 … x8=0x2` → `pc region … libld.so` → re-delivered → `CRASH sig=7` → exit 135 |
| SA | SIGPWR self-raise loop | exactly 501 stops, `SIGPWR fast path` lines, `storm stop>500` kill |

Build the harness with `-pthread`. **New required check:** the harness must fork from
a *pthread* (mirroring the app) and confirm the fix — i.e. the harness's
`--version` run now goes through `spawn_server_thread`, and every ptrace op must
succeed (no ESRCH, no `pc=0x0` line).

### 8.3 Device run — the acceptance criteria

After the fix the log must show, in order:

1. `elfloader: build r12 …` (first line — the stamp works).
2. `fault stop sig=31 code=1 … syscall=99 x8=0x63` **with a real `pc=` and
   `pc region … libld.so`, `Seccomp: 2`** — i.e. GETSIGINFO/GETREGSET succeeded.
3. More `syscall=293` (rseq) stops, plus the same for worker threads created from
   `libc.so.6` (TRACECLONE working).
4. rtld continues: `transferring control`, lazy bindings, thread pool, then
   `Bun v1.3.14 (Linux arm64)`.
5. `opencode server listening on http://127.0.0.1:4096`.
6. No SIGPWR storm (r8 already proved this) and no `hb state=t wchan=ptrace_stop`
   beyond the first stop.
7. Kotlin flips to `OpenCodeState.Running`; `/api/health` returns 200.

If (2) still prints `pc=0x0` → the fork did not land on the parent thread; check that
`spawn_server_thread` really contains the `fork()` and that nothing forks earlier.

If (1)–(5) pass but health still times out → the notes' open suspects apply: the
ENOSYS fixups wedging Bun's worker wake-loop, not ptrace. Then look at the
`fault stop … st=` sequence and the SIGPWR counters in that log.

---

## 9. Files touched / reference map

| File | What to change |
|------|----------------|
| `agent-core/src/androidMain/c/elfloader.c` | everything above; only this file |
| `androidApp/src/main/jniLibs/arm64-v8a/libelfloader.so` | regenerated by `scripts/build-elfloader.sh` (hand-committed binary — no Gradle native wiring, deliberate) |
| `thoughts/2026-10-05-opencode-elfloader-issues.md` | append a "round 12" section when r12 lands (house style: root cause, fixes, local + device results, `Open:` list) |
| `agent-core/src/androidMain/kotlin/com/agent/code/opencode/ElfLoader.kt` | unchanged |
| `agent-core/src/commonMain/kotlin/com/agent/code/opencode/OpenCodeManager.kt` | unchanged (spawn at :122, `delay(1000)` at :142, `waitForServer` at :206) |

Key line numbers (current file):

```
elfloader.c:40    ELFLOADER_ROUND
elfloader.c:444   child_main()
elfloader.c:485   log open O_APPEND (parent truncates, r10)
elfloader.c:722   PTRACE_TRACEME in the child
elfloader.c:751   handle_fault_stop()
elfloader.c:920   nativeSpawn (JNI)
elfloader.c:946   fork()                <-- moves into spawn_server_thread
elfloader.c:968   g_child_pid = pid
elfloader.c:971   pthread_create(drain_loop)  <-- becomes pthread_create(spawn_server_thread)
elfloader.c:992   service_one()
elfloader.c:1007  best-effort SETOPTIONS in service_one (ESRCH on device) <-- delete
elfloader.c:1032  drain_loop()
elfloader.c:1059  nativeCheck (JNI)
elfloader.c:1066  SETOPTIONS from JNI thread <-- delete
elfloader.c:1071  WNOHANG backup sweep       <-- delete
elfloader.c:1080  hb heartbeat (keep)
```

---

## 10. One-paragraph prompt for the implementer

> Edit only `agent-core/src/androidMain/c/elfloader.c`. Move the `fork()` out of
> `Java_…_nativeSpawn` into a new `spawn_server_thread` (the request comes from a
> malloc'd `struct spawn_req` copied from the JNI strings), so the thread that forks
> is also the thread that runs the existing blocking `waitpid(-1, __WALL)` drain
> loop and issues every ptrace request (Linux requires `child->parent == current`).
> Set `TRACECLONE|EXITKILL` from that thread with a bounded ESRCH retry after the
> fork. Delete the JNI-thread SETOPTIONS and the WNOHANG sweep in `nativeCheck`,
> keeping the exit-code read and the heartbeat. Bump `ELFLOADER_ROUND` to `r12`,
> rebuild with `scripts/build-elfloader.sh`, rebuild the APK, and hand the log back
> here for reading against §8.3.
