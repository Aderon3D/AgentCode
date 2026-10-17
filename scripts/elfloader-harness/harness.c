/* Host harness for the elfloader drain loop — run with ./run.sh, no device.
 *
 * Includes the REAL elfloader.c so it exercises the same drain_loop /
 * service_one / handle_fault_stop and the single-spawn gate, not a
 * reimplementation. Must obey the parent==tracer rule: the forking thread
 * drains in the SAME thread (so main forks and drains here).
 *
 * Asserts (1) drain_loop observes a traced child's stops and returns on its
 * exit — the waitpid(-1) selection that waitpid(-pgid) silently never sees;
 * and (2) the single-spawn gate refuses a concurrent spawn, then re-acquires.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <pthread.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/ptrace.h>

#include "elfloader.c"

static long now_ms(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec*1000L+t.tv_nsec/1000000L;}

static pid_t   g_kill_pid;
static volatile int g_drained;

static void *killer(void *a){ (void)a; usleep(300000); kill(g_kill_pid, SIGKILL); return NULL; }
static void *watchdog(void *a){ (void)a; usleep(2000000); if(!g_drained){ printf("TEST1 FAIL: drain_loop hung\n"); fflush(stdout); _exit(2);} return NULL; }

static int test1_drain_sees_child(void){
    struct spawn sp; memset(&sp, 0, sizeof sp);
    snprintf(sp.log, sizeof sp.log, "/tmp/elfloader-harness-spawn.log");
    sp.log_set = 1; sp.pid = 0;
    __atomic_store_n(&sp.exit_code, 0, __ATOMIC_RELEASE);

    pid_t pid = fork();
    if (pid == 0){
        if (ptrace(PTRACE_TRACEME,0,0,0)!=0) _exit(127);
        raise(SIGSTOP);            /* first stop: drain must observe it */
        for(;;) pause();
        _exit(0);
    }
    __atomic_store_n(&sp.pid, pid, __ATOMIC_RELEASE);
    g_kill_pid = pid;
    pthread_t k; pthread_create(&k,NULL,killer,NULL);
    pthread_t w; pthread_create(&w,NULL,watchdog,NULL);

    drain_loop(&sp);               /* blocks until leader exit */
    g_drained = 1;
    pthread_join(k,NULL); pthread_join(w,NULL);

    int ec = __atomic_load_n(&sp.exit_code, __ATOMIC_ACQUIRE);
    /* child killed by SIGKILL -> childStatus code 100+SIGKILL(9) = 109 */
    printf("TEST1 %s: drain_loop returned, exit_code=%d (expect 109)\n", ec==109?"PASS":"FAIL", ec);
    return ec==109?0:1;
}

static int test2_gate(void){
    if (spawn_gate_acquire() != 0){ printf("TEST2 FAIL: first acquire refused\n"); return 1; }
    long t0 = now_ms();
    int r = spawn_gate_acquire();          /* must block ~3s then refuse */
    long dt = now_ms() - t0;
    int ok_refuse = (r == -1 && dt >= 2900 && dt < 5000);
    spawn_gate_release();
    int ok_reacquire = (spawn_gate_acquire() == 0);
    spawn_gate_release();
    printf("TEST2 %s: concurrent->%d after %ldms (expect -1,~3s); reacquire=%s\n",
           (ok_refuse&&ok_reacquire)?"PASS":"FAIL", r, dt, ok_reacquire?"ok":"BAD");
    return (ok_refuse&&ok_reacquire)?0:1;
}

int main(void){
    int fail = 0;
    fail |= test1_drain_sees_child();
    fail |= test2_gate();
    printf("%s\n", fail? "HARNESS FAIL":"HARNESS PASS");
    return fail;
}
