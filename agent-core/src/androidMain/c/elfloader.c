#include <jni.h>

#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <asm/ptrace.h>
#include <elf.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/ucontext.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <dlfcn.h>

/* asm-generic/siginfo.h si_code for seccomp-generated SIGSYS (uapi: SYS_SECCOMP=1);
 * bionic headers don't expose the name. */
#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif

extern char **environ;

#define LD_STACK_SIZE (8u * 1024u * 1024u)
#define LD_MAX_ARGS 40
#define LD_MAX_ENV 240
#define LD_MAX_PHDR 64

/* Build stamp: round tag + compile time, printed as the first log line of
 * every spawn so a pasted device log self-identifies its build. Bump the
 * round tag when shipping; __DATE__/__TIME__ make it drift-proof. */
#define ELFLOADER_ROUND "r28"
#define ELFLOADER_BUILD ELFLOADER_ROUND " " __DATE__ " " __TIME__

typedef struct {
    uintptr_t bias;
    uintptr_t phdr;
    unsigned phnum;
    uintptr_t entry;
} LdInfo;

static size_t c_len(const char *s) {
    size_t n = 0;
    while (s[n]) n++;
    return n;
}

static int c_eq(const char *a, const char *b, size_t n) {
    for (size_t i = 0; i < n; i++) {
        if (a[i] != b[i]) return 0;
        if (a[i] == 0) return 1;
    }
    return 1;
}

static void c_cpy(char *d, const char *a, const char *b) {
    while (*a) *d++ = *a++;
    while (*b) *d++ = *b++;
    *d = 0;
}

/* ponytail: raw svc write — no PLT/GOT/libc dependency, so logging keeps
 * working if the process image changes under us mid-rtld. */
static long k_write(int fd, const void *buf, size_t n) {
    register long x8 __asm__("x8") = SYS_write;
    register long x0 __asm__("x0") = fd;
    register const void *x1 __asm__("x1") = buf;
    register long x2 __asm__("x2") = (long)n;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory", "cc");
    return x0;
}

static void w_all(int fd, const char *s) {
    size_t n = c_len(s), w = 0;
    while (w < n) {
        ssize_t r = k_write(fd, s + w, n - w);
        if (r <= 0) break;
        w += (size_t)r;
    }
}

static void w_hex(uint64_t v) {
    char b[18];
    b[0] = '0'; b[1] = 'x';
    for (int i = 0; i < 16; i++) {
        int d = (v >> (60 - i * 4)) & 0xf;
        b[2 + i] = d < 10 ? '0' + d : 'a' + d - 10;
    }
    k_write(2, b, 18);
}

static void w_dec(long v) {
    char b[24];
    int i = 24;
    int neg = v < 0;
    unsigned long n = neg ? (unsigned long)(-v) : (unsigned long)v;
    if (!n) b[--i] = '0';
    while (n) { b[--i] = (char)('0' + n % 10); n /= 10; }
    if (neg) b[--i] = '-';
    k_write(2, b + i, (size_t)(24 - i));
}

static void on_crash(int sig, siginfo_t *si, void *uc) {
    /* seccomp RET_TRAP (Android zygote allowlist): blocked syscall — usually
     * rtld's set_robust_list/rseq — must return -ENOSYS, else the caller keeps
     * garbage in x0 and dies later. Emulate the error and resume. */
    if (sig == SIGSYS && si) {
        w_all(2, "elfloader: SIGSYS trap syscall=");
        w_dec(si->si_syscall);
        w_all(2, " pc=");
        w_hex((uint64_t)((ucontext_t *)uc)->uc_mcontext.pc);
        w_all(2, "\n");
        ((ucontext_t *)uc)->uc_mcontext.regs[0] = (uint64_t)(int64_t)(-ENOSYS);
        return;
    }
    /* ponytail: canary fault from the delivery self-test — skip the store
     * (aarch64 STR is 4 bytes) and continue. Remove with the self-test. */
    if (si && si->si_addr == (void *)0x10) {
        w_all(2, "elfloader: handler delivery ok (self-test)\n");
        ((ucontext_t *)uc)->uc_mcontext.pc += 4;
        return;
    }
    w_all(2, "\nelfloader: CRASH sig=");
    char nb[16];
    int n = sig, i = 0;
    if (n == 0) nb[i++] = '0';
    else { char t[8]; int j = 0; while (n) { t[j++] = '0' + n % 10; n /= 10; } while (j) nb[i++] = t[--j]; }
    nb[i] = 0;
    w_all(2, nb);
    w_all(2, " addr=");
    w_hex((uint64_t)si->si_addr);
    ucontext_t *u = (ucontext_t *)uc;
    w_all(2, " pc=");
    w_hex((uint64_t)u->uc_mcontext.pc);
    w_all(2, " code=");
    w_hex((uint64_t)(uint32_t)si->si_code);
    w_all(2, "\n");
    _exit(128 + sig);
}

/* Kernel's aarch64 rt_sigaction layout: {handler, flags, restorer, mask}
 * = 32 bytes. Bionic's userland struct sigaction is {handler, flags, mask}
 * = 24 bytes and bionic translates field-by-field in sigaction(); passing
 * the userland struct directly to the raw syscall reads/writes 8 bogus
 * bytes past it. Use the kernel layout explicitly. restorer stays 0 (arm64
 * kernel does not need SA_RESTORER; it uses the vdso trampoline). */
struct ksa {
    void *handler;
    unsigned long flags;
    void *restorer;
    unsigned long mask;
};

static int k_sigaction(int sig, const struct ksa *act, struct ksa *old) {
    return (int)syscall(SYS_rt_sigaction, sig, act, old, (size_t)8);
}

static int k_sigaltstack(const stack_t *ss, stack_t *old) {
    return (int)syscall(SYS_sigaltstack, ss, old);
}

/* Register with BOTH paths: bionic sigaction() first so libsigchain's chain
 * table records our handler (harmless if unused), then raw rt_sigaction last
 * so the actual kernel slot holds on_crash directly, bypassing ART's
 * trampoline which walks state clobbered/evicted in this forked child. */
static void arm_handlers(void) {
    sigset_t unblock;
    sigemptyset(&unblock);
    syscall(SYS_rt_sigprocmask, SIG_SETMASK, &unblock, (void *)0, (size_t)8);

    struct sigaction b;
    memset(&b, 0, sizeof b);
    b.sa_sigaction = on_crash;
    b.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&b.sa_mask);
    int bionic_r = sigaction(SIGSEGV, &b, NULL);
    bionic_r |= sigaction(SIGSYS, &b, NULL);

    struct ksa k;
    memset(&k, 0, sizeof k);
    k.handler = (void *)on_crash;
    k.flags = (unsigned long)(SA_SIGINFO | SA_ONSTACK);
    int okr = 0;
    okr += k_sigaction(SIGSEGV, &k, NULL);
    okr += k_sigaction(SIGBUS, &k, NULL);
    okr += k_sigaction(SIGILL, &k, NULL);
    okr += k_sigaction(SIGABRT, &k, NULL);
    okr += k_sigaction(SIGSYS, &k, NULL);
    w_all(2, "elfloader: handlers armed: bionic=");
    if (bionic_r != 0) w_all(2, "FAIL ");
    else w_all(2, "ok ");
    w_all(2, "kernel=");
    if (okr == 0) w_all(2, "ok on_crash=");
    else w_all(2, "FAIL ");
    w_hex((uint64_t)(uintptr_t)on_crash);
    w_all(2, "\n");
}

static int line_match(const char *s, size_t n) {
    static const char *k[] = {"opencode", "libld", "libc.so", "libm.so", "libpthread", "libdl",
                              "libelfloader", "stack", "libtree", "libgit2",
                              "[heap]", "[vdso]", "[vvar]", NULL};
    for (int i = 0; k[i]; i++) {
        size_t m = c_len(k[i]);
        if (m > n) continue;
        for (size_t j = 0; j + m <= n; j++) {
            size_t t = 0;
            while (t < m && s[j + t] == k[i][t]) t++;
            if (t == m) return 1;
        }
    }
    return 0;
}

static void dump_maps(void) {
    w_all(2, "elfloader: maps begin\n");
    int s = open("/proc/self/maps", O_RDONLY);
    if (s < 0) {
        w_all(2, "elfloader: maps open FAIL\n");
        return;
    }
    static char lb[4096];
    size_t ln = 0;
    char b[2048];
    ssize_t r;
    while ((r = read(s, b, sizeof b)) > 0) {
        for (ssize_t i = 0; i < r; i++) {
            if (ln < sizeof lb - 1) lb[ln++] = b[i];
            if (b[i] == '\n') {
                if (line_match(lb, ln)) {
                    ssize_t w = 0;
                    while (w < (ssize_t)ln) {
                        ssize_t k = write(2, lb + w, ln - (size_t)w);
                        if (k <= 0) break;
                        w += k;
                    }
                }
                ln = 0;
            }
        }
    }
    w_all(2, "elfloader: maps end\n");
    close(s);
}

static void die(const char *m1, const char *m2) {
    w_all(2, "elfloader: ");
    w_all(2, m1);
    if (m2) {
        w_all(2, ": ");
        w_all(2, m2);
    }
    w_all(2, "\n");
    _exit(127);
}

static ssize_t rd_full(int fd, void *buf, size_t n, off_t off) {
    size_t got = 0;
    while (got < n) {
        ssize_t r = pread(fd, (char *)buf + got, n - got, (off_t)(off + got));
        if (r <= 0) return r == 0 ? (ssize_t)got : r;
        got += (size_t)r;
    }
    return (ssize_t)got;
}

static int hexv(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return 0;
}

/* Mappings the child still needs after the fixed-window maps go in
 * (crash handler + libc PLT). Never evict these. */
static int keep_line(const char *l) {
    return strstr(l, "[stack]") || strstr(l, "[vdso]") || strstr(l, "[vvar]") ||
           strstr(l, "[sigframe]") || strstr(l, "libelfloader") ||
           strstr(l, "libc.so") || strstr(l, "libdl.so") || strstr(l, "libm.so") ||
           strstr(l, "libpthread") || strstr(l, "linker");
}

/* Clear [lo,hi) for the ET_EXEC fixed-address maps. ART's heap
 * ([anon:dalvik-main space]) lives at 0x02000000-0x12000000 on 64-bit
 * Android — inside opencode's window — and MAP_FIXED over it silently
 * corrupts the child. The child never runs JVM code again, so evicting
 * is safe. Returns evicted count, or -1 if a required mapping (or a
 * munmap failure) is in the way. */
static int clear_window(uint64_t lo, uint64_t hi) {
    int s = open("/proc/self/maps", O_RDONLY);
    if (s < 0) return -1;
    static char lb[512];
    size_t ln = 0;
    int evicted = 0;
    char b[2048];
    ssize_t r;
    while ((r = read(s, b, sizeof b)) > 0) {
        for (ssize_t i = 0; i < r; i++) {
            if (b[i] == '\n') {
                lb[ln] = 0;
                uint64_t a = 0, e = 0;
                const char *q = lb;
                while (*q && *q != '-' && *q != ' ') { a = a * 16 + (uint64_t)hexv(*q); q++; }
                if (*q == '-') q++;
                while (*q && *q != ' ' && *q != '-') { e = e * 16 + (uint64_t)hexv(*q); q++; }
                if (e > lo && a < hi) {
                    if (keep_line(lb)) {
                        w_all(2, "elfloader: KEEP COLLISION ");
                        w_all(2, lb);
                        w_all(2, "\n");
                        close(s);
                        return -1;
                    }
                    /* Unmap only the part inside [lo,hi): a VMA that merely
                     * straddles the window edge must keep its outside half —
                     * munmapping the whole VMA evicts live memory below lo /
                     * above hi that opencode does not own. */
                    uint64_t ca = a > lo ? a : lo;
                    uint64_t ce = e < hi ? e : hi;
                    if (munmap((void *)(uintptr_t)ca, (size_t)(ce - ca)) != 0) {
                        w_all(2, "elfloader: evict FAIL ");
                        w_all(2, lb);
                        w_all(2, "\n");
                        close(s);
                        return -1;
                    }
                    w_all(2, "elfloader: evicted ");
                    w_all(2, lb);
                    w_all(2, "\n");
                    evicted++;
                }
                ln = 0;
            } else if (ln < sizeof lb - 1) {
                lb[ln++] = b[i];
            }
        }
    }
    close(s);
    return evicted;
}

static LdInfo map_any(const char *path, long pagesz, int must_dyn) {
    LdInfo out = {0, 0, 0, 0};
    int fd = open(path, O_RDONLY);
    if (fd < 0) die("open failed", path);
    Elf64_Ehdr eh;
    if (rd_full(fd, &eh, sizeof eh, 0) != (ssize_t)sizeof eh) die("read ehdr failed", path);
    if (memcmp(eh.e_ident, "\177ELF", 4) != 0 || eh.e_ident[EI_CLASS] != ELFCLASS64 ||
        eh.e_ident[EI_DATA] != ELFDATA2LSB || eh.e_machine != EM_AARCH64 ||
        (eh.e_type != ET_DYN && (must_dyn || eh.e_type != ET_EXEC))) {
        die("bad ELF", path);
    }
    if (eh.e_phnum == 0 || eh.e_phnum > LD_MAX_PHDR) die("bad loader phnum", path);
    /* e_phentsize is straight from the (possibly corrupt) header; an oversized
     * value would read past Elf64_Phdr ph[LD_MAX_PHDR] on the stack. */
    if (eh.e_phentsize != sizeof(Elf64_Phdr)) die("bad loader phentsize", path);
    Elf64_Phdr ph[LD_MAX_PHDR];
    if (rd_full(fd, ph, eh.e_phentsize * eh.e_phnum, (off_t)eh.e_phoff) !=
        (ssize_t)(eh.e_phentsize * eh.e_phnum)) {
        die("read loader phdrs failed", path);
    }
    uint64_t lo = ~0ULL, hi = 0;
    for (int i = 0; i < eh.e_phnum; i++) {
        if (ph[i].p_type != PT_LOAD) continue;
        uint64_t a = ph[i].p_vaddr & ~((uint64_t)pagesz - 1);
        uint64_t b = (ph[i].p_vaddr + ph[i].p_memsz + (uint64_t)pagesz - 1) & ~((uint64_t)pagesz - 1);
        if (a < lo) lo = a;
        if (b > hi) hi = b;
    }
    if (hi <= lo) die("no LOAD", path);
    if (eh.e_type == ET_EXEC) {
        w_all(2, "elfloader: window ");
        w_hex(lo);
        w_all(2, "-");
        w_hex(hi);
        w_all(2, "\n");
        int ev = clear_window(lo, hi);
        if (ev < 0) die("required mapping inside window", path);
        if (ev == 0) w_all(2, "elfloader: window clear\n");
    }
    uintptr_t bias;
    if (eh.e_type == ET_DYN) {
        void *res = mmap(NULL, (size_t)(hi - lo), PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (res == MAP_FAILED) die("reserve failed", path);
        bias = (uintptr_t)res - (uintptr_t)lo;
    } else {
        bias = 0;
    }
    for (int i = 0; i < eh.e_phnum; i++) {
        if (ph[i].p_type != PT_LOAD) continue;
        int prot = 0;
        if (ph[i].p_flags & PF_R) prot |= PROT_READ;
        if (ph[i].p_flags & PF_W) prot |= PROT_WRITE;
        if (ph[i].p_flags & PF_X) prot |= PROT_EXEC;
        uintptr_t a = bias + ph[i].p_vaddr;
        uintptr_t ap = a & ~((uintptr_t)pagesz - 1);
        off_t off = (off_t)(ph[i].p_offset & ~((uint64_t)pagesz - 1));
        size_t len = (size_t)((a + ph[i].p_filesz) - ap);
        if (len == 0) continue;
        if (mmap((void *)ap, len, prot, MAP_PRIVATE | MAP_FIXED, fd, off) == MAP_FAILED) {
            die("map seg failed", path);
        }
        uintptr_t file_end = a + ph[i].p_filesz;
        uintptr_t mem_end = (a + ph[i].p_memsz + (uintptr_t)pagesz - 1) & ~((uintptr_t)pagesz - 1);
        uintptr_t anon_start = (file_end + (uintptr_t)pagesz - 1) & ~((uintptr_t)pagesz - 1);
        if (mem_end > anon_start) {
            if (mmap((void *)anon_start, (size_t)(mem_end - anon_start), prot | PROT_WRITE | PROT_READ,
                     MAP_PRIVATE | MAP_FIXED | MAP_ANONYMOUS, -1, 0) == MAP_FAILED) {
                die("map bss failed", path);
            }
        }
        if (ph[i].p_memsz > ph[i].p_filesz) {
            memset((void *)file_end, 0, (size_t)(mem_end - file_end));
        }
    }
    close(fd);
    out.bias = bias;
    out.phnum = eh.e_phnum;
    out.entry = bias + eh.e_entry;
    out.phdr = 0;
    for (int i = 0; i < eh.e_phnum; i++) {
        if (ph[i].p_type != PT_LOAD) continue;
        if (eh.e_phoff >= ph[i].p_offset && eh.e_phoff < ph[i].p_offset + ph[i].p_filesz) {
            out.phdr = bias + ph[i].p_vaddr + (eh.e_phoff - ph[i].p_offset);
            break;
        }
    }
    if (out.phdr == 0) die("no phdr map", path);
    return out;
}

/* Never inline this at any -O level: the sp/entry values must arrive in
 * explicit operand registers. Hardcoding x0/x1 in basic asm breaks when GCC
 * inlines the static function (the ABI argument registers are then whatever
 * the allocator left there) — observed as SIGILL jumping into our own text. */
__attribute__((noinline)) static void enter_loader(void *sp, void *entry) {
    __asm__ volatile(
        "mov sp, %0\n"
        "br %1\n"
        :
        : "r"(sp), "r"(entry)
        : );
}

/* LD_PRELOAD=<libdir>/libdnsshim.so, resolved in the parent (nativeSpawn) where
 * dladdr is safe — dladdr takes the dynamic-loader lock and is not
 * async-signal-safe, and child_main runs post-fork. */
static char g_dnsshim_preload[4160];

static void child_main(const char *loader, const char *glibc, const char *binary,
                       const char *workdir, const char *logpath,
                       const char **uargs, int nargs) {
    /* Copy every string onto our own stack FIRST: GetStringUTFChars may
     * return pointers into the ART heap, which clear_window() evicts. */
    char b_load[4160], b_glibc[4160], b_bin[4160], b_work[4160], b_log[4160];
    char abuf[LD_MAX_ARGS][512];
    const char *cargs[LD_MAX_ARGS];
    if (c_len(loader) >= 4160 || c_len(glibc) >= 4160 || c_len(binary) >= 4160 ||
        c_len(workdir) >= 4160 || c_len(logpath) >= 4160) {
        _exit(127);
    }
    c_cpy(b_load, loader, "");
    c_cpy(b_glibc, glibc, "");
    c_cpy(b_bin, binary, "");
    c_cpy(b_work, workdir, "");
    c_cpy(b_log, logpath, "");
    loader = b_load; glibc = b_glibc; binary = b_bin; workdir = b_work; logpath = b_log;
    for (int i = 0; i < nargs; i++) {
        if (c_len(uargs[i]) >= sizeof abuf[0]) _exit(127);
        c_cpy(abuf[i], uargs[i], "");
        cargs[i] = abuf[i];
    }
    uargs = cargs;

    /* Same for the environment: environ pointers may also reference ART
     * heap memory that clear_window() evicts; rtld parses env early. */
    static char earena[65536];
    const char *cenv[LD_MAX_ENV];
    int cenvc = 0;
    size_t eoff = 0;
    for (char **e = environ; *e && cenvc < LD_MAX_ENV; e++) {
        size_t n = c_len(*e) + 1;
        if (eoff + n > sizeof earena) break;
        memcpy(earena + eoff, *e, n);
        cenv[cenvc++] = earena + eoff;
        eoff += n;
    }

    /* O_APPEND only — the PARENT truncates the log before fork (nativeSpawn);
     * truncating here races the drain thread's startup line and erases it. */
    int lf = open(logpath, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (lf < 0) _exit(127);
    dup2(lf, 1);
    dup2(lf, 2);
    int dn = open("/dev/null", O_RDONLY);
    if (dn >= 0) {
        dup2(dn, 0);
        if (dn > 2) close(dn);
    }
    if (lf > 2) close(lf);
    /* build stamp — first line of every log (stderr is the log by now),
     * identifies the shipped build from a pasted log alone. */
    w_all(2, "elfloader: build " ELFLOADER_BUILD "\n");
    if (workdir && workdir[0] && chdir(workdir) != 0) die("chdir failed", workdir);
    arm_handlers(); /* early: works without altstack (kernel falls back to sp) */

    long pagesz = (long)getauxval(AT_PAGESZ);
    if (pagesz <= 0) pagesz = 4096;
    LdInfo ld = map_any(loader, pagesz, 1);
    w_all(2, "elfloader: mapped ld\n");
#ifdef LD_TEST_VERSION
    static char testprog[4160];
    c_cpy(testprog, glibc, "/libc.so.6");
    const char *progpath = testprog;
#else
    const char *progpath = binary;
#endif
    LdInfo prog = map_any(progpath, pagesz, 0);
    w_all(2, "elfloader: mapped prog\n");

    /* Allocate altstack/sbrk only AFTER the fixed-window maps above, so
     * MAP_FIXED can never silently clobber them (that breaks the crash
     * handler: fault inside handler = raw signal kill, no log output). */
    void *altsp = MAP_FAILED;
    {
        stack_t ss;
        ss.ss_sp = altsp = mmap(NULL, 65536, PROT_READ | PROT_WRITE,
                                MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        ss.ss_size = 65536;
        ss.ss_flags = 0;
        w_all(2, "elfloader: altstack ");
        if (altsp == MAP_FAILED) {
            w_all(2, "mmap FAIL\n");
        } else {
            w_hex((uint64_t)(uintptr_t)altsp);
            w_all(2, "\n");
            stack_t cur;
            if (k_sigaltstack(&ss, NULL) == 0 &&
                k_sigaltstack(NULL, &cur) == 0 && cur.ss_sp == ss.ss_sp)
                w_all(2, "elfloader: altstack ok (kernel)\n");
            else
                w_all(2, "elfloader: altstack FAIL\n");
        }
        arm_handlers();
    }
    {
        void *b1 = sbrk(16384);
        w_all(2, b1 == (void *)-1 ? "elfloader: sbrk FAIL\n" : "elfloader: sbrk ok\n");
        uint64_t v = 0x12345678, r = 0, orig = 0;
        __asm__ volatile("mrs %0, tpidr_el0" : "=r"(orig));
        __asm__ volatile("msr tpidr_el0, %0" :: "r"(v));
        __asm__ volatile("mrs %0, tpidr_el0" : "=r"(r));
        __asm__ volatile("msr tpidr_el0, %0" :: "r"(orig));
        w_all(2, r == v ? "elfloader: tp ok\n" : "elfloader: tp FAIL\n");
        if (altsp != MAP_FAILED) {
            volatile char probe = *(volatile char *)altsp;
            (void)probe;
            w_all(2, "elfloader: altstack probe ok\n");
        }
    }
    w_all(2, "elfloader: brk ");
    w_hex((uint64_t)(uintptr_t)sbrk(0));
    w_all(2, "\n");

    const char *nargv[LD_MAX_ARGS];
    int nargc = 0, progidx = 0;
    progidx = nargc;
    nargv[nargc++] = progpath;
#ifndef LD_TEST_VERSION
    for (int i = 0; i < nargs && nargc < LD_MAX_ARGS - 1; i++) {
        nargv[nargc++] = uargs[i];
    }
#endif

    const char *nenv[LD_MAX_ENV];
    int nenvc = 0;
    for (int i = 0; i < cenvc && nenvc < LD_MAX_ENV - 2; i++) {
        if (c_eq(cenv[i], "LD_LIBRARY_PATH=", 16)) continue;
        if (c_eq(cenv[i], "LD_PRELOAD=", 11)) continue;
        if (c_eq(cenv[i], "LD_AUDIT=", 9)) continue;
        if (c_eq(cenv[i], "LD_HWCAP_MASK=", 14)) continue;
        nenv[nenvc++] = cenv[i];
    }
    char ldvar[4160];
    if (c_len(glibc) > 4096) die("glibc path too long", glibc);
    c_cpy(ldvar, "LD_LIBRARY_PATH=", glibc);
    nenv[nenvc++] = ldvar;
    /* LD_DEBUG=all dumps the dynamic linker's full relocation/version trace to
     * the log on every spawn — huge, and it leaks loader internals into
     * user-exported logs. Gate it behind ELFLOADER_LD_DEBUG=1, read from the
     * copied cenv[] (environ is unsafe here: clear_window() evicted it). */
    for (int i = 0; i < cenvc; i++) {
        if (c_eq(cenv[i], "ELFLOADER_LD_DEBUG=1", 20) && nenvc < LD_MAX_ENV) {
            nenv[nenvc++] = "LD_DEBUG=all";
            break;
        }
    }

    /* Android app processes have no HOME, and glibc getpwuid has no passwd
     * entry for the app uid — uv_os_homedir then returns ENOENT and Bun dies
     * at startup. HOME = install dir (dirname of the binary): exists,
     * writable, opencode state lands next to the log. */
    int has_home = 0;
    for (int i = 0; i < nenvc; i++)
        if (c_eq(nenv[i], "HOME=", 5)) { has_home = 1; break; }
    if (!has_home && nenvc < LD_MAX_ENV) {
        static char homevar[4160];
        const char *slash = strrchr(binary, '/');
        size_t dl = slash ? (size_t)(slash - binary) : 0;
        if (dl > 0 && dl < sizeof homevar - 6) {
            memcpy(homevar, "HOME=", 5);
            memcpy(homevar + 5, binary, dl);
            homevar[5 + dl] = 0;
            nenv[nenvc++] = homevar;
        }
    }

    /* DNS: glibc getaddrinfo reads /etc/resolv.conf (absent on Android), and
     * glibc's internal resolv opens are NOT LD_PRELOAD-interposable
     * (verified empirically) — so preload a getaddrinfo shim instead.
     * The LD_PRELOAD value is resolved in the parent (nativeSpawn), where
     * dladdr is safe; OC_RESOLV_CONF points at resolv.conf next to the binary
     * (written by OpenCodeManager.start). */
    if (g_dnsshim_preload[0] && nenvc < LD_MAX_ENV) {
        nenv[nenvc++] = g_dnsshim_preload;
    }
    if (nenvc < LD_MAX_ENV) {
        static char resolvvar[4160];
        static const char rel[] = "/resolv.conf";
        const char *slash = strrchr(binary, '/');
        size_t dl = slash ? (size_t)(slash - binary) : 0;
        if (dl > 0 && dl + sizeof rel < sizeof resolvvar - 15) {
            memcpy(resolvvar, "OC_RESOLV_CONF=", 15);
            memcpy(resolvvar + 15, binary, dl);
            memcpy(resolvvar + 15 + dl, rel, sizeof rel);
            nenv[nenvc++] = resolvvar;
        }
    }

    w_all(2, "elfloader: spawning:");
    for (int i = 0; i < nargc; i++) {
        w_all(2, " ");
        w_all(2, nargv[i]);
    }
    w_all(2, "\n");

    char rnd[16];
    int rf = open("/dev/urandom", O_RDONLY);
    if (rf >= 0) {
        size_t got = 0;
        while (got < 16) {
            ssize_t r = read(rf, rnd + got, 16 - got);
            if (r <= 0) break;
            got += (size_t)r;
        }
        close(rf);
        if (got < 16) die("urandom short", 0);
    } else {
        uintptr_t s = (uintptr_t)&rf ^ (uintptr_t)getpid();
        for (int i = 0; i < 16; i++) {
            rnd[i] = (char)(s >> ((i % 8) * 8));
            s = s * 6364136223846793005ULL + 1;
        }
    }

    void *st = mmap(NULL, LD_STACK_SIZE, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (st == MAP_FAILED) die("stack mmap failed", 0);
    uint8_t *top = (uint8_t *)st + LD_STACK_SIZE;

    uint64_t argaddr[LD_MAX_ARGS];
    uint64_t envaddr[LD_MAX_ENV];
    uint8_t *p = top;
    for (int i = 0; i < nargc; i++) {
        size_t n = c_len(nargv[i]) + 1;
        p -= n;
        memcpy(p, nargv[i], n);
        argaddr[i] = (uint64_t)p;
    }
    for (int i = 0; i < nenvc; i++) {
        size_t n = c_len(nenv[i]) + 1;
        p -= n;
        memcpy(p, nenv[i], n);
        envaddr[i] = (uint64_t)p;
    }
    p -= 16;
    memcpy(p, rnd, 16);
    uint64_t rndaddr = (uint64_t)p;

    uint64_t keys[19], vals[19];
    int ac = 0;
    keys[ac] = AT_PHDR; vals[ac++] = prog.phdr;
    keys[ac] = AT_PHENT; vals[ac++] = sizeof(Elf64_Phdr);
    keys[ac] = AT_PHNUM; vals[ac++] = prog.phnum;
    keys[ac] = AT_BASE; vals[ac++] = ld.bias;
    keys[ac] = AT_ENTRY; vals[ac++] = prog.entry;
    keys[ac] = AT_UID; vals[ac++] = (uint64_t)getuid();
    keys[ac] = AT_EUID; vals[ac++] = (uint64_t)geteuid();
    keys[ac] = AT_GID; vals[ac++] = (uint64_t)getgid();
    keys[ac] = AT_EGID; vals[ac++] = (uint64_t)getegid();
    keys[ac] = AT_CLKTCK; vals[ac++] = getauxval(AT_CLKTCK) ? getauxval(AT_CLKTCK) : 100;
    keys[ac] = AT_PAGESZ; vals[ac++] = (uint64_t)pagesz;
    keys[ac] = AT_HWCAP; vals[ac++] = getauxval(AT_HWCAP);
    keys[ac] = AT_HWCAP2; vals[ac++] = getauxval(AT_HWCAP2);
    keys[ac] = AT_SECURE; vals[ac++] = 0;
    keys[ac] = AT_RANDOM; vals[ac++] = rndaddr;
    keys[ac] = AT_EXECFN; vals[ac++] = argaddr[progidx];
    keys[ac] = AT_SYSINFO_EHDR; vals[ac++] = 0;
    keys[ac] = AT_NULL; vals[ac++] = 0;

    size_t frame = 8 + (size_t)(nargc + 1) * 8 + (size_t)(nenvc + 1) * 8 + (size_t)ac * 16;
    p = (uint8_t *)(((uintptr_t)p - frame) & ~(uintptr_t)15);
    uint64_t *w = (uint64_t *)p;
    *w++ = (uint64_t)nargc;
    for (int i = 0; i < nargc; i++) *w++ = argaddr[i];
    *w++ = 0;
    for (int i = 0; i < nenvc; i++) *w++ = envaddr[i];
    *w++ = 0;
    for (int i = 0; i < ac; i++) {
        *w++ = keys[i];
        *w++ = vals[i];
    }

    w_all(2, "elfloader: jumping\n");
    dump_maps();
    arm_handlers();
    {
        struct ksa cur;
        memset(&cur, 0, sizeof cur);
        long rr = syscall(SYS_rt_sigaction, SIGSEGV, (void *)0, &cur, (size_t)8);
        w_all(2, "elfloader: readback ret=");
        w_hex((uint64_t)rr);
        w_all(2, " handler=");
        w_hex((uint64_t)(uintptr_t)cur.handler);
        w_all(2, " expect=");
        w_hex((uint64_t)(uintptr_t)on_crash);
        w_all(2, cur.handler == (void *)on_crash ? " live\n" : " GONE\n");
        memset(&cur, 0, sizeof cur);
        rr = syscall(SYS_rt_sigaction, SIGSYS, (void *)0, &cur, (size_t)8);
        w_all(2, "elfloader: sigsys ret=");
        w_hex((uint64_t)rr);
        w_all(2, " handler=");
        w_hex((uint64_t)(uintptr_t)cur.handler);
        w_all(2, cur.handler == (void *)on_crash ? " live\n" : " GONE\n");
    }
    /* ponytail: delivery self-test — if the kernel can't reach on_crash even
     * with the slot verified live, we die here instead of inside rtld.
     * Remove once device delivery is confirmed. */
    w_all(2, "elfloader: delivery self-test...\n");
    *(volatile int *)0x10 = 0;
    /* ponytail: fault-PC capture — kernel STOPS us on the faulting signal so
     * nativeCheck can log si_addr/code/pc, then detaches (signal discarded)
     * and we refault into normal handler delivery. */
    int notrace = 0;
    for (int i = 0; i < cenvc; i++)
        if (c_len(cenv[i]) >= 18 && !memcmp(cenv[i], "ELFLOADER_NOTRACE=", 18) && cenv[i][18] != '0')
            notrace = 1;
    if (notrace)
        w_all(2, "elfloader: traceme skipped (ELFLOADER_NOTRACE)\n");
    else if (syscall(SYS_ptrace, PTRACE_TRACEME, 0L, 0L, 0L) != 0)
        w_all(2, "elfloader: traceme FAIL\n");
    else
        w_all(2, "elfloader: traceme ok\n");
    enter_loader(p, (void *)ld.entry);
    die("enter returned", 0);
}

/* ---- per-spawn state (r28) ----------------------------------------------
 * Every drain-side variable used to be a process-global static, so two
 * concurrent spawns (serve + run, or multi-agent) corrupted each other: the
 * second nativeSpawn overwrote g_child_log / g_child_pid / g_pwr_stops /
 * g_rate_* mid-flight, and the static counters inside service_one /
 * handle_fault_stop / nativeCheck were shared. Each spawn now owns a
 * struct spawn, and nativeCheck(pid) looks it up in the registry below.
 *
 * The drain still waits on waitpid(-1): the kernel does NOT report a
 * tracee's ptrace-stops through a process-group selection — waitpid(-pgid)
 * returns nothing for a stopped tracee (verified on this kernel with a
 * minimal repro) — and waitpid(leader_pid) misses the clone-thread stops
 * that PTRACE_O_TRACECLONE produces (they carry a fresh tid). Only
 * waitpid(-1) sees the whole tracee thread group, so it is process-wide and
 * only one drain may run at a time: enforced by the single-spawn gate in
 * nativeSpawn/drain_loop. True concurrency needs per-tid tracking (#200). */
struct spawn {
    pid_t pid;                 /* leader pid; negative = fork -errno */
    char  log[4160];
    int   log_set;
    volatile int exit_code;    /* 0 = running; else childStatus code (atomic) */
    /* SIGPWR storm gate (was g_pwr_stops / g_rate_sec / g_rate_n). The device
     * storm is SIGPWR (30), not SIGABRT (6): Bun/opencode's scheduler wake-
     * loop tkill()s workers. Breaker in handle_fault_stop: fast re-delivery
     * from stop 4, kill on a high RATE (never a total count). 2000/s separates
     * benign ~9/s wakes from a wedged thousands/s loop. */
    int     pwr_stops;
    time_t  rate_sec;
    int     rate_n;
    /* forensics counters (were function-local statics, shared across spawns) */
    unsigned ev_n;             /* service_one drain-event counter */
    int     seccomp_stops;     /* handle_fault_stop SIGSYS counter */
    int     sys_fault_dumped;  /* handle_fault_stop /proc/status dump budget */
    int     hb_n;              /* nativeCheck heartbeat divider */
    struct spawn *next;        /* registry linkage */
};

/* Registry so nativeCheck(pid) finds the right spawn. The single-spawn gate
 * means at most one is live at a time, but entries are never removed (see
 * drain_loop): nativeCheck must keep answering for a pid after exit, and the
 * structs are leaked by design. Guarded by a mutex because nativeCheck runs on
 * Kotlin's poll thread while spawn threads insert. Newest entry wins a lookup
 * (prepend), so a reused pid resolves to the current spawn. */
static struct spawn *g_spawns;
static pthread_mutex_t g_spawn_mu = PTHREAD_MUTEX_INITIALIZER;

static void spawn_register(struct spawn *sp) {
    pthread_mutex_lock(&g_spawn_mu);
    sp->next = g_spawns;
    g_spawns = sp;
    pthread_mutex_unlock(&g_spawn_mu);
}
/* Lookup by pid. Spawns are never unregistered, so the returned pointer stays
 * valid for the process lifetime (nativeCheck never dereferences after NULL). */
static struct spawn *spawn_find(pid_t pid) {
    struct spawn *found = NULL;
    pthread_mutex_lock(&g_spawn_mu);
    for (struct spawn *s = g_spawns; s; s = s->next)
        if (s->pid == pid) { found = s; break; }
    pthread_mutex_unlock(&g_spawn_mu);
    return found;
}

static void *drain_loop(void *arg);

/* Single-spawn gate: waitpid(-1) is process-wide, so only one drain may run.
 * nativeSpawn waits a short grace period for a prior drain (whose child the
 * caller just stopped) to finish, then refuses with -EBUSY rather than let a
 * second drain steal the first child's stop events. The spawn thread clears
 * the gate when drain_loop returns. */
static pthread_mutex_t g_gate_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_gate_cv = PTHREAD_COND_INITIALIZER;
static int g_gate_active;

static int spawn_gate_acquire(void) {
    struct timespec dl;
    clock_gettime(CLOCK_REALTIME, &dl);
    dl.tv_sec += 3;
    pthread_mutex_lock(&g_gate_mu);
    while (g_gate_active) {
        if (pthread_cond_timedwait(&g_gate_cv, &g_gate_mu, &dl) == ETIMEDOUT) {
            pthread_mutex_unlock(&g_gate_mu);
            return -1;
        }
    }
    g_gate_active = 1;
    pthread_mutex_unlock(&g_gate_mu);
    return 0;
}
static void spawn_gate_release(void) {
    pthread_mutex_lock(&g_gate_mu);
    g_gate_active = 0;
    pthread_cond_signal(&g_gate_cv);
    pthread_mutex_unlock(&g_gate_mu);
}


/* Parent-side handling of a traced child stop: forensics into logfd
 * (si_syscall, x8, the maps line owning pc, Seccomp status) plus recovery —
 * a seccomp SIGSYS stop gets x0 = -ENOSYS and PTRACE_CONT (stays attached so
 * every later seccomp signal is also intercepted instead of killing the
 * child). Real faults are re-delivered with PTRACE_CONT so the child's own
 * handler reports the CRASH line; tracing continues either way.
 * Returns 1 = child resumed. */
static int handle_fault_stop(struct spawn *sp, pid_t pid, int logfd) {
    siginfo_t si;
    struct user_pt_regs regs;
    memset(&si, 0, sizeof si);
    memset(&regs, 0, sizeof regs);
    errno = 0;
    long gr = ptrace(PTRACE_GETSIGINFO, pid, 0, &si);
    int gerrno = errno;
    /* Auto-attach stop of a clone child arrives as SIGSTOP — swallow it.
     * Re-delivering puts the thread back in a stop loop and every new
     * worker thread freezes before it runs a single instruction. */
    if (si.si_signo == SIGSTOP) {
        ptrace(PTRACE_CONT, pid, 0, 0);
        return 1;
    }
    struct iovec iov = { &regs, sizeof regs };
    ptrace(PTRACE_GETREGSET, pid, (void *)(long)NT_PRSTATUS, &iov);

    /* tid = the stopped thread; spid = si_pid, the tkill sender (SI_TKILL) —
     * identifies the loop's sender vs receiver threads.
     * st = SIGPWR stop counter for this spawn (fast path / cap below). */
    if (si.si_signo == SIGPWR) sp->pwr_stops++;
    /* SIGPWR storm fast path — checked BEFORE any logging or /proc
     * forensics: the device storm is Bun/opencode's scheduler wake-loop
     * tkill()ing SIGPWR at its workers (spid = main thread); stops 1-3 keep
     * the full forensics below, stop 4+ re-deliver silently except the
     * every-64th heartbeat line. Kill on RATE, not total count: a healthy
     * server logs benign wakes forever at ~9/s, so the old absolute cap
     * (500) killed every run that lived ~50s (r14). A wedged loop is
     * thousands/s. */
    if (si.si_signo == SIGPWR && sp->pwr_stops > 3) {
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        if (now.tv_sec != sp->rate_sec) { sp->rate_sec = now.tv_sec; sp->rate_n = 0; }
        if (++sp->rate_n > 2000) {
            if (logfd >= 0)
                k_write(logfd, "elfloader: SIGPWR rate>2000/s, killing child\n", 45);
            ptrace(PTRACE_KILL, pid, 0, 0);
            return 1;
        }
        if (logfd >= 0 && (sp->pwr_stops & 63) == 4) {
            char hb[80];
            int hn = snprintf(hb, sizeof hb, "elfloader: SIGPWR fast path st=%d\n", sp->pwr_stops);
            if (hn > 0) k_write(logfd, hb, (size_t)hn);
        }
        ptrace(PTRACE_CONT, pid, 0, (void *)(long)si.si_signo);
        return 1;
    }
    /* forensics budget: the fault-stop line + GETSIGINFO echo are ~150 B each;
     * seccomp SIGSYS churn (one per denied syscall, thousands/second) alone
     * grows the log ~1 KB/s. Print the first 4 seccomp stops in full (they're
     * the interesting onset), then only every 64th as a heartbeat, plus any
     * non-seccomp fault (real crash) which is rare and always worth a line.
     * Only the log writes below are gated — the ENOSYS register fix-up that
     * intercepts the syscall must still run on every stop. */
    /* (r28) per-spawn counters — was function-local statics, shared across
     * concurrent spawns. */
    int seccomp = (si.si_code == SYS_SECCOMP);
    if (seccomp) sp->seccomp_stops++;
    int quiet = seccomp && sp->seccomp_stops > 4 && (sp->seccomp_stops & 63) != 0;
    char b[320];
    int n;
    if (!quiet) {
        n = snprintf(b, sizeof b,
                         "elfloader: fault stop sig=%d code=%d tid=%d spid=%ld st=%d addr=0x%llx pc=0x%llx syscall=%d x8=0x%llx\n",
                         si.si_signo, si.si_code,
                         (int)pid, (long)si.si_pid, sp->pwr_stops,
                         (unsigned long long)si.si_addr, (unsigned long long)regs.pc,
                         si.si_code == SYS_SECCOMP ? si.si_syscall : -1,
                         (unsigned long long)regs.regs[8]);
        if (logfd >= 0 && n > 0) k_write(logfd, b, (size_t)n);
        if (gr < 0 && logfd >= 0) {
            n = snprintf(b, sizeof b, "elfloader: GETSIGINFO errno=%d\n", gerrno);
            if (n > 0) k_write(logfd, b, (size_t)n);
        }
    }

    /* maps/status buffers — maps read moved below: only the first SIGPWR
     * stop and real faults pay for it, the storm path stays cheap. */
    static char big[262144]; /* /proc/<pid>/maps outgrows any stack buffer */
    int fd;

    /* Seccomp: / Seccomp_filters: from /proc/<pid>/status — first 4 faults
     * only; the rest are the identical select() SIGSYS storm (one dump per
     * worker thread ate the whole log budget at r17). */
    if (si.si_code != SYS_SECCOMP || sp->sys_fault_dumped < 4) {
        if (si.si_code == SYS_SECCOMP) sp->sys_fault_dumped++;
        snprintf(b, sizeof b, "/proc/%d/status", pid);
        fd = open(b, O_RDONLY);
        if (fd >= 0) {
            if (logfd >= 0) {
                ssize_t got = read(fd, big, sizeof big - 1);
                if (got > 0) {
                    big[got] = 0;
                    for (char *s = big; s && *s; ) {
                        char *eol = strchr(s, '\n');
                        if (eol) *eol = 0;
                        if (strncmp(s, "Seccomp", 7) == 0 ||
                            strncmp(s, "TracerPid", 9) == 0 ||
                            strncmp(s, "SigBlk", 6) == 0 ||
                            strncmp(s, "SigIgn", 6) == 0 ||
                            strncmp(s, "SigCgt", 6) == 0) {
                            /* Sig* masks: SigCgt bit 0x20 = SIGABRT disposition
                             * caught; SigIgn 0x20 = ignored; else SIG_DFL. */
                            k_write(logfd, "elfloader: ", 11);
                            k_write(logfd, s, c_len(s));
                            k_write(logfd, "\n", 1);
                        }
                        s = eol ? eol + 1 : 0;
                    }
                }
            }
            close(fd);
        }
    }

    if (si.si_code == SYS_SECCOMP) {
        regs.regs[0] = (uint64_t)(int64_t)(-ENOSYS);
        struct iovec wiov = { &regs, sizeof regs };
        ptrace(PTRACE_SETREGSET, pid, (void *)(long)NT_PRSTATUS, &wiov);
        /* Stay attached: interception happens before disposition dispatch,
         * so a runtime SIGSYS reset can't kill us. */
        ptrace(PTRACE_CONT, pid, 0, 0);
        return 1;
    }

    /* the maps line containing pc — names the file that executed the svc */
    snprintf(b, sizeof b, "/proc/%d/maps", pid);
    fd = open(b, O_RDONLY);
    if (fd >= 0) {
        size_t got = 0;
        ssize_t r;
        while (got < sizeof big - 1 && (r = read(fd, big + got, sizeof big - 1 - got)) > 0)
            got += (size_t)r;
        close(fd);
        big[got] = 0;
        for (char *s = big; s && logfd >= 0; ) {
            unsigned long lo = 0, hi = 0;
            if (sscanf(s, "%lx-%lx", &lo, &hi) == 2 &&
                (unsigned long long)regs.pc >= lo && (unsigned long long)regs.pc < hi) {
                char *eol = strchr(s, '\n');
                size_t ln = eol ? (size_t)(eol - s) : c_len(s);
                if (ln > 400) ln = 400;
                k_write(logfd, "elfloader: pc region ", 21);
                k_write(logfd, s, ln);
                k_write(logfd, "\n", 1);
                break;
            }
            s = strchr(s, '\n');
            if (s) s++;
        }
    }

    /* instruction word at pc: svc #0 = parked in syscall when the signal
     * arrived; other = interrupted mid-code (async delivery). */
    errno = 0;
    long ins = (long)ptrace(PTRACE_PEEKTEXT, pid, (void *)(uintptr_t)regs.pc, 0);
    if (errno == 0 && logfd >= 0) {
        n = snprintf(b, sizeof b, "elfloader: ins pc=0x%llx val=0x%08lx\n",
                     (unsigned long long)regs.pc, (unsigned long)(ins & 0xffffffff));
        if (n > 0) k_write(logfd, b, (size_t)n);
    }

    /* fp-chain backtrace — receivers of the SIGPWR storm park in
     * futex/sigsuspend svc; the fp chain names their callers. */
    if ((si.si_signo == SIGPWR || si.si_signo == SIGABRT || si.si_signo == SIGSEGV ||
         si.si_signo == SIGBUS || si.si_signo == SIGILL || si.si_signo == SIGTRAP) && logfd >= 0) {
        uint64_t fp = regs.regs[29];
        int nf = 0, bn = 0;
        char bt[256];
        bt[0] = 0;
        while (nf < 8 && fp && !(fp & 7)) {
            errno = 0;
            uint64_t nxt = (uint64_t)ptrace(PTRACE_PEEKDATA, pid, (void *)(uintptr_t)fp, 0);
            if (errno) break;
            errno = 0;
            uint64_t lr = (uint64_t)ptrace(PTRACE_PEEKDATA, pid, (void *)(uintptr_t)(fp + 8), 0);
            if (errno) break;
            bn += snprintf(bt + bn, sizeof bt - (size_t)bn, " 0x%llx", (unsigned long long)lr);
            if (bn >= (int)sizeof bt - 24) break;
            fp = nxt;
            nf++;
        }
        if (nf > 0) {
            k_write(logfd, "elfloader: bt", 13);
            k_write(logfd, bt, (size_t)bn);
            k_write(logfd, "\n", 1);
        }
    }
    /* Real fault/signal: re-deliver it so the child's own handler runs
     * (CRASH line + _exit) — keep tracing. The SIGPWR storm returned above
     * once past stop 3; only its first 3 stops reach this line. */
    ptrace(PTRACE_CONT, pid, 0, (void *)(long)si.si_signo);
    return 1;
}

/* Per-spawn request: the spawn thread forks with this, the child keeps its
 * post-fork copy of the strings, the parent frees its side right after fork
 * (JNI string pointers must not outlive the nativeSpawn call). */
struct spawn_req {
    char loader[4160], glibc[4160], binary[4160], work[4160], log[4160];
    const char *args[LD_MAX_ARGS];
    char argbuf[LD_MAX_ARGS][512];
    int nargs;
    struct spawn *sp; /* per-spawn drain context (r28) */
};

/* The spawning thread IS the tracer: kernel ptrace_check_attach() accepts a
 * request only when child->parent == current (the task that forked), so this
 * thread does fork, the blocking waitpid drain loop, and every ptrace call.
 * r8-r11 forked on the JNI thread and serviced stops from a second pthread:
 * waitpid worked (process-scoped) but every ptrace got ESRCH, the child
 * parked at its first seccomp trap and Kotlin's 15s timeout killed it. */
static void *spawn_server_body(void *arg) {
    struct spawn_req *r = arg;
    struct spawn *sp = r->sp;
    pid_t pid = fork();
    if (pid == 0) {
        child_main(r->loader, r->glibc, r->binary, r->work, r->log,
                   r->args, r->nargs);
        _exit(127);
    }
    free(r); /* child owns its post-fork copy; parent no longer needs it */
    if (pid < 0) {
        __atomic_store_n(&sp->pid, (pid_t)-errno, __ATOMIC_RELEASE);
        __atomic_store_n(&sp->exit_code, -errno, __ATOMIC_RELEASE);
        return NULL;
    }
    __atomic_store_n(&sp->pid, pid, __ATOMIC_RELEASE);
    drain_loop(sp);
    return NULL;
}

/* Thread entry: spawn+drain, then release the single-spawn gate so a later
 * nativeSpawn (e.g. a run after the serve was stopped) can proceed. Releases
 * on the fork-failure path too, which never reaches drain_loop. */
static void *spawn_server_thread(void *arg) {
    spawn_server_body(arg);
    spawn_gate_release();
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_agent_code_opencode_ElfLoader_nativeSpawn(JNIEnv *env, jobject thiz,
                                                   jstring jLoader, jstring jGlibc, jstring jBinary,
                                                   jstring jWork, jstring jLog, jobjectArray jArgs) {
    const char *loader = (*env)->GetStringUTFChars(env, jLoader, 0);
    const char *glibc = (*env)->GetStringUTFChars(env, jGlibc, 0);
    const char *binary = (*env)->GetStringUTFChars(env, jBinary, 0);
    const char *work = (*env)->GetStringUTFChars(env, jWork, 0);
    const char *logp = (*env)->GetStringUTFChars(env, jLog, 0);
    /* A NULL here means GetStringUTFChars threw OutOfMemoryError: release only
     * the non-NULL pointers (NULL to ReleaseStringUTFChars is invalid) and bail
     * with the pending exception. */
    if (!loader || !glibc || !binary || !work || !logp) {
        if (logp) (*env)->ReleaseStringUTFChars(env, jLog, logp);
        if (work) (*env)->ReleaseStringUTFChars(env, jWork, work);
        if (binary) (*env)->ReleaseStringUTFChars(env, jBinary, binary);
        if (glibc) (*env)->ReleaseStringUTFChars(env, jGlibc, glibc);
        if (loader) (*env)->ReleaseStringUTFChars(env, jLoader, loader);
        return -EINVAL;
    }
    /* Resolve libdnsshim.so's directory here, in the parent: child_main runs
     * post-fork, where a non-async-signal-safe call like dladdr can deadlock
     * if another thread held the dynamic-loader lock at fork() time. */
    jsize n = (*env)->GetArrayLength(env, jArgs);
    if (n < 0 || n > LD_MAX_ARGS) {
        (*env)->ReleaseStringUTFChars(env, jLog, logp);
        (*env)->ReleaseStringUTFChars(env, jWork, work);
        (*env)->ReleaseStringUTFChars(env, jBinary, binary);
        (*env)->ReleaseStringUTFChars(env, jGlibc, glibc);
        (*env)->ReleaseStringUTFChars(env, jLoader, loader);
        return -E2BIG;
    }
    struct spawn_req *req = calloc(1, sizeof *req);
    if (!req) {
        (*env)->ReleaseStringUTFChars(env, jLog, logp);
        (*env)->ReleaseStringUTFChars(env, jWork, work);
        (*env)->ReleaseStringUTFChars(env, jBinary, binary);
        (*env)->ReleaseStringUTFChars(env, jGlibc, glibc);
        (*env)->ReleaseStringUTFChars(env, jLoader, loader);
        return -ENOMEM;
    }
    snprintf(req->loader, sizeof req->loader, "%s", loader);
    snprintf(req->glibc, sizeof req->glibc, "%s", glibc);
    snprintf(req->binary, sizeof req->binary, "%s", binary);
    snprintf(req->work, sizeof req->work, "%s", work);
    snprintf(req->log, sizeof req->log, "%s", logp);
    req->nargs = n;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jArgs, i);
        if (!s) {
            free(req);
            (*env)->ReleaseStringUTFChars(env, jLog, logp);
            (*env)->ReleaseStringUTFChars(env, jWork, work);
            (*env)->ReleaseStringUTFChars(env, jBinary, binary);
            (*env)->ReleaseStringUTFChars(env, jGlibc, glibc);
            (*env)->ReleaseStringUTFChars(env, jLoader, loader);
            return -EINVAL;
        }
        const char *a = (*env)->GetStringUTFChars(env, s, 0);
        if (!a) {
            (*env)->DeleteLocalRef(env, s);
            free(req);
            (*env)->ReleaseStringUTFChars(env, jLog, logp);
            (*env)->ReleaseStringUTFChars(env, jWork, work);
            (*env)->ReleaseStringUTFChars(env, jBinary, binary);
            (*env)->ReleaseStringUTFChars(env, jGlibc, glibc);
            (*env)->ReleaseStringUTFChars(env, jLoader, loader);
            return -EINVAL;
        }
        snprintf(req->argbuf[i], sizeof req->argbuf[i], "%s", a);
        req->args[i] = req->argbuf[i];
        (*env)->ReleaseStringUTFChars(env, s, a);
        (*env)->DeleteLocalRef(env, s);
    }
    (*env)->ReleaseStringUTFChars(env, jLog, logp);
    (*env)->ReleaseStringUTFChars(env, jWork, work);
    (*env)->ReleaseStringUTFChars(env, jBinary, binary);
    (*env)->ReleaseStringUTFChars(env, jGlibc, glibc);
    (*env)->ReleaseStringUTFChars(env, jLoader, loader);

    /* r28: per-spawn drain context so nativeCheck(pid) finds the right one. */
    struct spawn *sp = calloc(1, sizeof *sp);
    if (!sp) { free(req); return -ENOMEM; }
    snprintf(sp->log, sizeof sp->log, "%s", req->log);
    sp->log_set = 1;
    sp->pid = 0;       /* sentinel: fork not done yet; spawn thread sets it */
    __atomic_store_n(&sp->exit_code, 0, __ATOMIC_RELEASE);
    req->sp = sp;
    /* Truncate the log HERE, before fork — the child only appends now, so
     * the spawn thread's lines can never be erased by the child's open. */
    int tfd = open(req->log, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (tfd >= 0) close(tfd);

    /* Single-spawn gate: waitpid(-1) is process-wide, so a second live drain
     * would steal this child's stop events. Wait briefly for a prior drain
     * (whose child the caller just stopped) to finish, else refuse. */
    if (spawn_gate_acquire() != 0) {
        free(sp);
        free(req);
        return -EBUSY;
    }

    pthread_t th;
    int pe = pthread_create(&th, NULL, spawn_server_thread, req);
    if (pe != 0) {
        spawn_gate_release();
        free(sp);
        free(req);
        return -pe;
    }
    pthread_detach(th);
    /* Wait for the fork to publish sp->pid (NOT g_child_pid — concurrent
     * spawns would collide on a shared global). */
    for (int i = 0; i < 500 && __atomic_load_n(&sp->pid, __ATOMIC_ACQUIRE) == 0; i++)
        usleep(1000);
    pid_t got = __atomic_load_n(&sp->pid, __ATOMIC_ACQUIRE);
    if (got == 0) return -ETIMEDOUT;
    if (got < 0) return got; /* -errno from fork() */
    /* Register once the pid is known so nativeCheck(pid) can find it. Entries
     * are never removed — see drain_loop. */
    spawn_register(sp);
    return (jint)got;
}

/* One waitpid+handle iteration, run on spawn_server_thread — the child's
 * parent task, the only task allowed to ptrace it (kernel requires
 * child->parent == current). Returns: 1 = event handled, 0 = no event
 * pending (WNOHANG), -1 = waitpid error (errno), 2 = leader exit recorded.
 * waitpid(-1) is the only selection that reports both the leader's stops and
 * the clone threads' stops; the single-spawn gate keeps other drains out. */
static int service_one(struct spawn *sp, int blocking, int *opts_set, int logfd) {
    int status = 0;
    /* -1 (any child): waitpid(leader_pid) drops the PTRACE_O_TRACECLONE
     * worker stops and waitpid(-pgid) reports no ptrace-stops at all. */
    pid_t t = waitpid(-1, &status, __WALL | (blocking ? 0 : WNOHANG));
    /* Diagnostics (ELFLOADER_TRACE=1): log every waitpid result. Reveals whether
     * a hang is ECHILD, a stuck 0, or an unhandled stop. */
    static int trace_inited;
    if (!trace_inited) { trace_inited = getenv("ELFLOADER_TRACE") != NULL; }
    if (trace_inited) {
        int e = (t < 0) ? errno : 0;
        char tb[160];
        int tn = snprintf(tb, sizeof tb,
                          "elfloader: TRACE wp(-1,blk=%d,__WALL%s) ret=%d err=%d st=0x%x\n",
                          blocking, blocking ? "" : "|WNOHANG",
                          (int)t, e, (unsigned)status);
        if (tn > 0) k_write(2, tb, (size_t)tn);
    }
    if (t == 0) return 0;
    if (t < 0) return -1;
    /* Quiet by default: first 40 events full, then every 256th. Bun's worker
     * churn produces thousands of clone/exit stops; logging each one drowned
     * the server-log tail out of the dump budget (r18). */
    sp->ev_n++;
    if (logfd >= 0 && (sp->ev_n <= 40 || (sp->ev_n & 0xff) == 0)) {
        char db[96];
        int dn = snprintf(db, sizeof db, "elfloader: drain ev tid=%d st=0x%x n=%u\n",
                          (int)t, (unsigned)status, sp->ev_n);
        if (dn > 0) k_write(logfd, db, (size_t)dn);
    }
    if (WIFSTOPPED(status)) {
        /* SETOPTIONS at the first stop from THIS thread (the parent task —
         * the only task whose ptrace the kernel accepts): the child is
         * parked at its main-thread pthread-init trap before any clone
         * exists, so TRACECLONE here covers every later worker. Mark
         * opts_set only on success — a failed attempt retries next stop. */
        if (!*opts_set &&
            ptrace(PTRACE_SETOPTIONS, t, 0,
                   (void *)(long)(PTRACE_O_TRACECLONE | PTRACE_O_EXITKILL)) == 0)
            *opts_set = 1;
        if (WSTOPSIG(status) == SIGTRAP && (status >> 16) != 0) {
            ptrace(PTRACE_CONT, t, 0, 0); /* clone/event stop */
            return 1;
        }
        handle_fault_stop(sp, t, logfd);
        return 1;
    }
    if (t != sp->pid) return 1; /* worker thread ended */
    if (WIFEXITED(status))
        __atomic_store_n(&sp->exit_code,
                         WEXITSTATUS(status) == 0 ? 1 : 2, __ATOMIC_RELEASE);
    else if (WIFSIGNALED(status))
        __atomic_store_n(&sp->exit_code, 100 + WTERMSIG(status), __ATOMIC_RELEASE);
    else
        __atomic_store_n(&sp->exit_code, 3, __ATOMIC_RELEASE);
    return 2;
}

/* Forensics thread: blocking waitpid, µs stop-to-resume latency. Every event
 * and error is logged (drain ev / drain err lines) so a device log shows
 * exactly what it saw and when. r28: takes the per-spawn context so a
 * concurrent spawn can't clobber the log path or counters. */
static void *drain_loop(void *arg) {
    struct spawn *sp = arg;
    int opts_set = 0;
    int logfd = sp->log_set ? open(sp->log, O_WRONLY | O_APPEND) : -1;
    if (logfd >= 0)
        k_write(logfd, "elfloader: drain thread up\n", 27);
    for (;;) {
        int r = service_one(sp, 1, &opts_set, logfd);
        if (r == 2) break;
        if (r < 0) {
            if (errno == EINTR) continue;
            if (logfd >= 0) {
                char eb[64];
                int en = snprintf(eb, sizeof eb,
                                  "elfloader: drain waitpid errno=%d\n", errno);
                if (en > 0) k_write(logfd, eb, (size_t)en);
            }
            break;
        }
    }
    if (logfd >= 0) {
        k_write(logfd, "elfloader: drain thread exit\n", 29);
        close(logfd);
    }
    /* Deliberately NOT unregistered: nativeCheck(pid) must keep returning the
     * exit code after the leader is gone (the pre-r28 global did, and Kotlin's
     * waitForServer relies on it to notice an early death). The struct is
     * leaked by design — a late nativeCheck could still hold the pointer, so
     * freeing it would race (ponytail: one small struct per spawn). */
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_agent_code_opencode_ElfLoader_nativeCheck(JNIEnv *env, jobject thiz, jint pid) {
    (void)env; (void)thiz;
    /* NO ptrace from here: the spawn thread owns the tracee (kernel requires
     * child->parent == current) and services stops itself; a second waiter
     * would race it for the same event. Heartbeat only.
     * r28: look up the spawn by pid (concurrent spawns each have their own). */
    struct spawn *sp = spawn_find((pid_t)pid);
    if (!sp) return 0; /* unknown / already reaped */
    int exit_now = __atomic_load_n(&sp->exit_code, __ATOMIC_ACQUIRE);
    /* Don't open the log on every 100ms poll: only the ~2s heartbeat writes,
     * and a dead child needs no I/O to report. Avoids Main-thread file I/O. */
    if (exit_now) return exit_now;
    if (sp->pid <= 0 || ++sp->hb_n < 20) return 0;
    sp->hb_n = 0;
    /* ~2s child-state heartbeat (Kotlin polls this every 100ms): leader
     * state letter + wchan + kernel stack — names "traced-parked",
     * "blocked-in-syscall-X" or "running" in the next pasted log. */
    int logfd = sp->log_set ? open(sp->log, O_WRONLY | O_APPEND) : -1;
    {
        char hb[1024];
        char state[2] = "?", wchan[48] = "0";
        char path[64];
        snprintf(path, sizeof path, "/proc/%d/status", sp->pid);
        int fd = open(path, O_RDONLY);
        if (fd >= 0) {
            char buf[2048];
            ssize_t got = read(fd, buf, sizeof buf - 1);
            close(fd);
            if (got > 0) {
                buf[got] = 0;
                char *s = strstr(buf, "State:");
                if (s) {
                    s += 6;
                    while (*s == ' ' || *s == '\t') s++;
                    state[0] = *s;
                }
            }
        }
        snprintf(path, sizeof path, "/proc/%d/wchan", sp->pid);
        fd = open(path, O_RDONLY);
        if (fd >= 0) {
            ssize_t got = read(fd, wchan, sizeof wchan - 1);
            close(fd);
            if (got > 0) wchan[got] = 0;
        }
        int hn = snprintf(hb, sizeof hb, "elfloader: hb state=%s wchan=%s",
                          state, wchan);
        snprintf(path, sizeof path, "/proc/%d/stack", sp->pid);
        fd = open(path, O_RDONLY);
        if (fd >= 0 && hn > 0 && hn < (int)sizeof hb - 8) {
            ssize_t got = read(fd, hb + hn, sizeof hb - 1 - (size_t)hn - 1);
            close(fd);
            if (got > 0) hn += (int)got;
        }
        if (hn > 0) {
            if (hb[hn - 1] != '\n' && hn < (int)sizeof hb - 1) hb[hn++] = '\n';
            if (logfd >= 0) k_write(logfd, hb, (size_t)hn);
        }
    }
    if (logfd >= 0) close(logfd);
    return exit_now;
}
