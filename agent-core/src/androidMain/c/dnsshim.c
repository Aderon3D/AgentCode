#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdarg.h>
#include <unistd.h>
#include <errno.h>
#include <netdb.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <netinet/in.h>

/*
 * opencode (glibc child on Android) resolves via libc getaddrinfo, which
 * reads /etc/resolv.conf — missing on Android, and glibc-internal opens are
 * NOT LD_PRELOAD-interposable (verified). So we interpose getaddrinfo itself
 * (called through the app's PLT) and run a minimal UDP A-record resolver
 * against the nameservers in $OC_RESOLV_CONF.
 *
 * ponytail ceilings: IPv4 A records only (no AAAA), no /etc/hosts, no
 * search domains. Add when IPv6-only or host aliases show up.
 */

static void dlog(const char *s) { ssize_t r = write(2, s, strlen(s)); (void)r; }

/* ---- open-family redirect: free, may catch non-glibc readers (c-ares) ---- */
static const char *redirect(const char *path) {
    if (path && strcmp(path, "/etc/resolv.conf") == 0) {
        const char *a = getenv("OC_RESOLV_CONF");
        if (a && *a) return a;
    }
    return path;
}

int openat(int dirfd, const char *path, int flags, ...) {
    static int (*real_openat)(int, const char *, int, ...);
    if (!real_openat) real_openat = dlsym(RTLD_NEXT, "openat");
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return real_openat(dirfd, redirect(path), flags, mode);
}

int open(const char *path, int flags, ...) {
    static int (*real_open)(const char *, int, ...);
    if (!real_open) real_open = dlsym(RTLD_NEXT, "open");
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return real_open(redirect(path), flags, mode);
}

int open64(const char *path, int flags, ...) {
    static int (*real_open64)(const char *, int, ...);
    if (!real_open64) real_open64 = dlsym(RTLD_NEXT, "open64");
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return real_open64(redirect(path), flags, mode);
}

FILE *fopen(const char *path, const char *mode) {
    static FILE *(*real_fopen)(const char *, const char *);
    if (!real_fopen) real_fopen = dlsym(RTLD_NEXT, "fopen");
    return real_fopen(redirect(path), mode);
}

/* ---- minimal DNS: A queries over UDP ---- */

static int read_nameservers(struct in_addr *ns, int max) {
    const char *paths[2];
    int np = 0;
    const char *e = getenv("OC_RESOLV_CONF");
    if (e && *e) paths[np++] = e;
    paths[np++] = "/etc/resolv.conf";
    for (int i = 0; i < np; i++) {
        FILE *f = fopen(paths[i], "r");
        if (!f) continue;
        char line[256];
        int c = 0;
        while (c < max && fgets(line, sizeof line, f)) {
            if (strncmp(line, "nameserver", 10) == 0) {
                char *p = line + 10;
                while (*p == ' ' || *p == '\t') p++;
                p[strcspn(p, "\r\n")] = 0; /* inet_pton rejects trailing \n */
                if (inet_pton(AF_INET, p, &ns[c]) == 1) c++;
            }
        }
        fclose(f);
        if (c > 0) return c;
    }
    ns[0].s_addr = htonl(0x08080808); /* 8.8.8.8 fallback */
    return 1;
}

static uint16_t rd16(const unsigned char *p) { return (uint16_t)((p[0] << 8) | p[1]); }

/* hosts file first (glibc order: files then dns) — Android HAS /etc/hosts */
static int hosts_lookup(const char *node, struct in_addr *out, int maxout) {
    FILE *f = fopen("/etc/hosts", "r");
    if (!f) return 0;
    char line[512];
    int got = 0;
    while (got < maxout && fgets(line, sizeof line, f)) {
        if (line[0] == '#' || line[0] == '\n') continue;
        char *p = line;
        char *sp = p;
        while (*sp && *sp != ' ' && *sp != '\t' && *sp != '\r' && *sp != '\n') sp++;
        char save = *sp;
        *sp = 0;
        struct in_addr a;
        int ok = inet_pton(AF_INET, p, &a) == 1;
        *sp = save;
        if (!ok) continue;
        char *saveptr = NULL;
        char *tok = strtok_r(sp, " \t\r\n", &saveptr);
        while (tok) {
            if (strcmp(tok, node) == 0) { out[got++] = a; break; }
            tok = strtok_r(NULL, " \t\r\n", &saveptr);
        }
    }
    fclose(f);
    return got;
}

static int query_a(const struct in_addr *ns, int nsn, const char *host,
                   struct in_addr *out, int maxout) {
    unsigned char q[512 + 256];
    static uint16_t txid = 0x4000;
    uint16_t id = ++txid;
    q[0] = id >> 8; q[1] = id & 0xff;
    q[2] = 0x01; q[3] = 0x00; /* RD */
    q[4] = 0; q[5] = 1;       /* QDCOUNT */
    q[6] = q[7] = q[8] = q[9] = q[10] = q[11] = 0;
    int i = 12;
    const char *h = host;
    while (*h) {
        const char *dot = strchr(h, '.');
        int len = dot ? (int)(dot - h) : (int)strlen(h);
        if (len <= 0 || len > 63) return 0;
        /* Running bound: label byte + label + root byte + qtype + qclass. */
        if (i + 1 + len + 5 > (int)sizeof q) return 0;
        q[i++] = (unsigned char)len;
        memcpy(q + i, h, len); i += len;
        if (!dot) break;
        h = dot + 1;
    }
    q[i++] = 0;
    q[i++] = 0; q[i++] = 1; /* A */
    q[i++] = 0; q[i++] = 1; /* IN */
    int qlen = i;

    int s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s < 0) return 0;
    struct timeval tv = {3, 0};
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);

    int got = 0;
    for (int attempt = 0; attempt < 2 && got == 0; attempt++) {
        for (int n = 0; n < nsn && got < maxout; n++) {
            struct sockaddr_in sa;
            memset(&sa, 0, sizeof sa);
            sa.sin_family = AF_INET;
            sa.sin_port = htons(53);
            sa.sin_addr = ns[n];
            if (sendto(s, q, qlen, 0, (struct sockaddr *)&sa, sizeof sa) < 0) continue;
            unsigned char r[2048];
            int rl = (int)recv(s, r, sizeof r, 0);
            if (rl < 12) continue;
            if (rd16(r) != id) continue;
            uint16_t flags = rd16(r + 2);
            if (!(flags & 0x8000)) continue;               /* QR */
            if ((flags & 0x000f) != 0) continue;           /* RCODE */
            uint16_t ancount = rd16(r + 6);
            int p = 12;
            /* skip question: qname + qtype + qclass */
            int qd = rd16(r + 4);
            for (int k = 0; k < qd && p < rl; k++) {
                while (p < rl && r[p] != 0) {
                    if ((r[p] & 0xc0) == 0xc0) { p += 2; goto qdone; }
                    p += r[p] + 1;
                }
                p++;
                qdone: p += 4;
            }
            for (int k = 0; k < ancount && p < rl; k++) {
                if ((r[p] & 0xc0) == 0xc0) p += 2;
                else { while (p < rl && r[p] != 0) p += r[p] + 1; p++; }
                if (p + 10 > rl) break;
                uint16_t type = rd16(r + p);
                uint16_t rdlen = rd16(r + p + 8);
                p += 10;
                if (p + rdlen > rl) break;
                if (type == 1 && rdlen == 4 && got < maxout) {
                    memcpy(&out[got++], r + p, 4);
                }
                p += rdlen;
            }
            if (got > 0) break;
        }
    }
    close(s);
    return got;
}

int getaddrinfo(const char *node, const char *service,
                const struct addrinfo *hints, struct addrinfo **res) {
    int want = hints ? hints->ai_family : AF_UNSPEC;
    char b[256];
    snprintf(b, sizeof b, "[dns-shim] getaddrinfo(%s, fam=%d)\n", node ? node : "(null)", want);
    dlog(b);
    if (!res) return EAI_SYSTEM;

    /* numeric addresses: never hit DNS (bun calls getaddrinfo("127.0.0.1")) */
    struct in_addr lit4;
    if (node && inet_pton(AF_INET, node, &lit4) == 1) {
        struct addrinfo *ai = calloc(1, sizeof *ai);
        struct sockaddr_in *sa = calloc(1, sizeof *sa);
        if (!ai || !sa) { free(ai); free(sa); return EAI_MEMORY; }
        sa->sin_family = AF_INET;
        sa->sin_port = service ? (uint16_t)htons((uint16_t)atoi(service)) : 0;
        sa->sin_addr = lit4;
        ai->ai_family = AF_INET;
        ai->ai_socktype = (hints && hints->ai_socktype) ? hints->ai_socktype : SOCK_STREAM;
        ai->ai_protocol = (hints && hints->ai_protocol) ? hints->ai_protocol : IPPROTO_TCP;
        ai->ai_addrlen = sizeof *sa;
        ai->ai_addr = (struct sockaddr *)sa;
        *res = ai;
        return 0;
    }
    *res = NULL;

    struct in6_addr lit6;
    if (node && inet_pton(AF_INET6, node, &lit6) == 1) {
        struct addrinfo *ai = calloc(1, sizeof *ai);
        struct sockaddr_in6 *sa = calloc(1, sizeof *sa);
        if (!ai || !sa) { free(ai); free(sa); return EAI_MEMORY; }
        sa->sin6_family = AF_INET6;
        sa->sin6_port = service ? (uint16_t)htons((uint16_t)atoi(service)) : 0;
        sa->sin6_addr = lit6;
        ai->ai_family = AF_INET6;
        ai->ai_socktype = (hints && hints->ai_socktype) ? hints->ai_socktype : SOCK_STREAM;
        ai->ai_protocol = (hints && hints->ai_protocol) ? hints->ai_protocol : IPPROTO_TCP;
        ai->ai_addrlen = sizeof *sa;
        ai->ai_addr = (struct sockaddr *)sa;
        *res = ai;
        return 0;
    }

    if (!node) {
        /* bind-style request */
        struct addrinfo *ai = calloc(1, sizeof *ai);
        struct sockaddr_in *sa = calloc(1, sizeof *sa);
        if (!ai || !sa) { free(ai); free(sa); return EAI_MEMORY; }
        sa->sin_family = AF_INET;
        sa->sin_port = service ? (uint16_t)htons((uint16_t)atoi(service)) : 0;
        sa->sin_addr.s_addr = (hints && (hints->ai_flags & AI_PASSIVE)) ? htonl(INADDR_ANY)
                                                                        : htonl(INADDR_LOOPBACK);
        ai->ai_family = AF_INET;
        ai->ai_socktype = (hints && hints->ai_socktype) ? hints->ai_socktype : SOCK_STREAM;
        ai->ai_protocol = (hints && hints->ai_protocol) ? hints->ai_protocol : IPPROTO_TCP;
        ai->ai_addrlen = sizeof *sa;
        ai->ai_addr = (struct sockaddr *)sa;
        *res = ai;
        return 0;
    }

    if (want != AF_UNSPEC && want != AF_INET) {
        dlog("[dns-shim] AF_INET6 request -> EAI_NONAME (no AAAA impl)\n");
        return EAI_NONAME;
    }

    struct in_addr addrs[16];
    int na = hosts_lookup(node, addrs, 16);
    if (na == 0) {
        struct in_addr ns[4];
        int nsn = read_nameservers(ns, 4);
        na = query_a(ns, nsn, node, addrs, 16);
    }
    if (na == 0) return EAI_NONAME;

    int socktype = (hints && hints->ai_socktype) ? hints->ai_socktype : SOCK_STREAM;
    int protocol = (hints && hints->ai_protocol) ? hints->ai_protocol : IPPROTO_TCP;
    uint16_t port = service ? (uint16_t)htons((uint16_t)atoi(service)) : 0;

    struct addrinfo *head = NULL, *tail = NULL;
    for (int i = 0; i < na; i++) {
        struct addrinfo *ai = calloc(1, sizeof *ai);
        struct sockaddr_in *sa = calloc(1, sizeof *sa);
        if (!ai || !sa) { free(ai); free(sa); break; }
        sa->sin_family = AF_INET;
        sa->sin_port = port;
        sa->sin_addr = addrs[i];
        ai->ai_family = AF_INET;
        ai->ai_socktype = socktype;
        ai->ai_protocol = protocol;
        ai->ai_addrlen = sizeof *sa;
        ai->ai_addr = (struct sockaddr *)sa;
        if (tail) tail->ai_next = ai; else head = ai;
        tail = ai;
    }
    if (!head) return EAI_NONAME;
    *res = head;
    dlog("[dns-shim] getaddrinfo ok\n");
    return 0;
}

void freeaddrinfo(struct addrinfo *res) {
    while (res) {
        struct addrinfo *n = res->ai_next;
        free(res->ai_addr);
        free(res);
        res = n;
    }
}
