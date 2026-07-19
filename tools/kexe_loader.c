#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <signal.h>
#include <stddef.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#if defined(__APPLE__)
#include <sandbox.h>
#endif

#if defined(__linux__)
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#endif

typedef int64_t (*kexe_fn6)(int64_t, int64_t, int64_t, int64_t, int64_t, int64_t);
typedef int64_t (*kexe_fn8)(int64_t, int64_t, int64_t, int64_t,
                            int64_t, int64_t, int64_t, int64_t);

#define KEXE_PAIR_CAPACITY 4096u
#define KEXE_KGRAPH_CAPACITY 4096u

struct kexe_context_v2 {
  uint64_t version;
  uint64_t fuel;
  uint64_t allow[4];
  int64_t (*cap_call)(struct kexe_context_v2 *, uint64_t, int64_t);
  int64_t (*pair_new)(struct kexe_context_v2 *, int64_t, int64_t);
  int64_t (*pair_first)(struct kexe_context_v2 *, int64_t);
  int64_t (*pair_second)(struct kexe_context_v2 *, int64_t);
  /* kgraph-* (ADR-2607198300): an all-integer EAVT datom store, the native
   * analog of kotoba-lang/kotoba's string/EDN-based kgraph-assert!/
   * kgraph-query -- this loader has no addressable guest buffer for EDN
   * text, so entity/attribute/value are caller-assigned integer ids. */
  int64_t (*kgraph_assert)(struct kexe_context_v2 *, int64_t, int64_t, int64_t);
  int64_t (*kgraph_get)(struct kexe_context_v2 *, int64_t, int64_t);
  int64_t (*kgraph_count)(struct kexe_context_v2 *, int64_t);
  int64_t (*kgraph_entity_at)(struct kexe_context_v2 *, int64_t, int64_t);
};

struct kexe_pair_v1 { int64_t first; int64_t second; };
struct kexe_datom_v1 { int64_t e; int64_t a; int64_t v; };

struct kexe_shared_v2 {
  struct kexe_context_v2 context;
  int64_t result;
  uint64_t completed;
  uint64_t pair_used;
  struct kexe_pair_v1 pairs[KEXE_PAIR_CAPACITY];
  uint64_t kgraph_used;
  struct kexe_datom_v1 datoms[KEXE_KGRAPH_CAPACITY];
};

_Static_assert(offsetof(struct kexe_context_v2, fuel) == 8, "fuel ABI drift");
_Static_assert(offsetof(struct kexe_context_v2, allow) == 16, "allow ABI drift");
_Static_assert(offsetof(struct kexe_context_v2, cap_call) == 48, "cap ABI drift");
_Static_assert(offsetof(struct kexe_context_v2, pair_new) == 56, "pair ABI drift");
_Static_assert(offsetof(struct kexe_context_v2, pair_first) == 64, "pair ABI drift");
_Static_assert(offsetof(struct kexe_context_v2, pair_second) == 72, "pair ABI drift");
_Static_assert(offsetof(struct kexe_context_v2, kgraph_assert) == 80, "kgraph ABI drift");
_Static_assert(offsetof(struct kexe_context_v2, kgraph_get) == 88, "kgraph ABI drift");
_Static_assert(offsetof(struct kexe_context_v2, kgraph_count) == 96, "kgraph ABI drift");
_Static_assert(offsetof(struct kexe_context_v2, kgraph_entity_at) == 104, "kgraph ABI drift");
_Static_assert(sizeof(((struct kexe_shared_v2 *)0)->pairs) == 65536,
               "pair arena size drift");
_Static_assert(sizeof(((struct kexe_shared_v2 *)0)->datoms) == 98304,
               "kgraph arena size drift");

static int parse_u64(const char *text, uint64_t *value) {
  if (text == NULL || *text < '0' || *text > '9') return -1;
  char *end = NULL;
  errno = 0;
  unsigned long long parsed = strtoull(text, &end, 10);
  if (errno == ERANGE || end == text || *end != '\0') return -1;
  *value = (uint64_t)parsed;
  return 0;
}

static int parse_ulong_decimal(const char *text, unsigned long *value) {
  if (text == NULL || *text < '0' || *text > '9') return -1;
  char *end = NULL;
  errno = 0;
  unsigned long parsed = strtoul(text, &end, 10);
  if (errno == ERANGE || end == text || *end != '\0') return -1;
  *value = parsed;
  return 0;
}

static int parse_i64(const char *text, int64_t *value) {
  if (text == NULL || (*text != '-' && (*text < '0' || *text > '9')) ||
      (*text == '-' && (text[1] < '0' || text[1] > '9'))) return -1;
  char *end = NULL;
  errno = 0;
  long long parsed = strtoll(text, &end, 10);
  if (errno == ERANGE || end == text || *end != '\0') return -1;
  *value = (int64_t)parsed;
  return 0;
}

static volatile sig_atomic_t supervisor_timed_out = 0;
static volatile sig_atomic_t supervised_pid = -1;

static int64_t checked_cap_call(struct kexe_context_v2 *context,
                                uint64_t cap_id, int64_t value) {
  if (context == NULL || context->version != 2 || cap_id > 255 ||
      (context->allow[cap_id / 64] & (UINT64_C(1) << (cap_id % 64))) == 0) {
    raise(SIGILL);
    return 0;
  }
  return value + 1;
}

static int64_t checked_pair_new(struct kexe_context_v2 *context,
                                int64_t first, int64_t second) {
  struct kexe_shared_v2 *shared = (struct kexe_shared_v2 *)context;
  if (context == NULL || context->version != 2 ||
      shared->pair_used >= KEXE_PAIR_CAPACITY) {
    raise(SIGILL);
    return 0;
  }
  uint64_t index = shared->pair_used++;
  shared->pairs[index].first = first;
  shared->pairs[index].second = second;
  return (int64_t)(index + 1);
}

static int64_t checked_pair_get(struct kexe_context_v2 *context,
                                int64_t handle, int second) {
  struct kexe_shared_v2 *shared = (struct kexe_shared_v2 *)context;
  if (context == NULL || context->version != 2 || handle <= 0 ||
      (uint64_t)handle > shared->pair_used) {
    raise(SIGILL);
    return 0;
  }
  struct kexe_pair_v1 *pair = &shared->pairs[(uint64_t)handle - 1];
  return second ? pair->second : pair->first;
}

static int64_t checked_pair_first(struct kexe_context_v2 *context, int64_t handle) {
  return checked_pair_get(context, handle, 0);
}

static int64_t checked_pair_second(struct kexe_context_v2 *context, int64_t handle) {
  return checked_pair_get(context, handle, 1);
}

static int64_t checked_kgraph_assert(struct kexe_context_v2 *context,
                                     int64_t e, int64_t a, int64_t v) {
  struct kexe_shared_v2 *shared = (struct kexe_shared_v2 *)context;
  if (context == NULL || context->version != 2 ||
      shared->kgraph_used >= KEXE_KGRAPH_CAPACITY) {
    raise(SIGILL);
    return 0;
  }
  uint64_t index = shared->kgraph_used++;
  shared->datoms[index].e = e;
  shared->datoms[index].a = a;
  shared->datoms[index].v = v;
  return 1;
}

/* Last-write-wins point lookup, matching kgraph-lang/kotoba's own
 * kgraph-query semantics for a single (entity, attribute) pair. */
static int64_t checked_kgraph_get(struct kexe_context_v2 *context,
                                  int64_t e, int64_t a) {
  struct kexe_shared_v2 *shared = (struct kexe_shared_v2 *)context;
  if (context == NULL || context->version != 2) {
    raise(SIGILL);
    return 0;
  }
  int64_t result = INT64_MIN;
  for (uint64_t i = 0; i < shared->kgraph_used; i++) {
    if (shared->datoms[i].e == e && shared->datoms[i].a == a) {
      result = shared->datoms[i].v;
    }
  }
  return result;
}

/* True (non-zero) exactly when entity `e` has ever been asserted with
 * attribute `a`, used by checked_kgraph_count/checked_kgraph_entity_at to
 * de-duplicate to the first occurrence without a separate seen-set. */
static int kgraph_entity_seen_before(const struct kexe_shared_v2 *shared,
                                     uint64_t upto, int64_t a, int64_t e) {
  for (uint64_t j = 0; j < upto; j++) {
    if (shared->datoms[j].a == a && shared->datoms[j].e == e) return 1;
  }
  return 0;
}

static int64_t checked_kgraph_count(struct kexe_context_v2 *context, int64_t a) {
  struct kexe_shared_v2 *shared = (struct kexe_shared_v2 *)context;
  if (context == NULL || context->version != 2) {
    raise(SIGILL);
    return 0;
  }
  int64_t count = 0;
  for (uint64_t i = 0; i < shared->kgraph_used; i++) {
    if (shared->datoms[i].a != a) continue;
    if (!kgraph_entity_seen_before(shared, i, a, shared->datoms[i].e)) count++;
  }
  return count;
}

static int64_t checked_kgraph_entity_at(struct kexe_context_v2 *context,
                                        int64_t a, int64_t index) {
  struct kexe_shared_v2 *shared = (struct kexe_shared_v2 *)context;
  if (context == NULL || context->version != 2 || index < 0) {
    raise(SIGILL);
    return 0;
  }
  int64_t seen = -1;
  for (uint64_t i = 0; i < shared->kgraph_used; i++) {
    if (shared->datoms[i].a != a) continue;
    if (kgraph_entity_seen_before(shared, i, a, shared->datoms[i].e)) continue;
    seen++;
    if (seen == index) return shared->datoms[i].e;
  }
  raise(SIGILL);
  return 0;
}

static int parse_allow(const char *text, uint64_t allow[4]) {
  if (strcmp(text, "-") == 0) return 0;
  const char *cursor = text;
  while (*cursor) {
    if (*cursor < '0' || *cursor > '9') return -1;
    char *end = NULL;
    errno = 0;
    unsigned long id = strtoul(cursor, &end, 10);
    if (errno == ERANGE || end == cursor || id > 255 ||
        (*end != ',' && *end != '\0')) return -1;
    allow[id / 64] |= UINT64_C(1) << (id % 64);
    if (*end == '\0') return 0;
    cursor = end + 1;
    if (*cursor == '\0') return -1;
  }
  return -1;
}

static void fail(const char *message) {
  fprintf(stderr, "kexe-loader: %s: %s\n", message, strerror(errno));
  exit(1);
}

static void trap_handler(int signal_number) {
  static const char sigill[] = "KEXE_TRAP {:kind :signal :signal :SIGILL}\n";
  static const char sigtrap[] = "KEXE_TRAP {:kind :signal :signal :SIGTRAP}\n";
  static const char sigfpe[] = "KEXE_TRAP {:kind :signal :signal :SIGFPE}\n";
  static const char sigbus[] = "KEXE_TRAP {:kind :signal :signal :SIGBUS}\n";
  static const char sigsegv[] = "KEXE_TRAP {:kind :signal :signal :SIGSEGV}\n";
  static const char sigxcpu[] = "KEXE_TRAP {:kind :signal :signal :SIGXCPU}\n";
  static const char sigalrm[] = "KEXE_TRAP {:kind :signal :signal :SIGALRM}\n";
#if defined(SIGSYS)
  static const char sigsys[] = "KEXE_TRAP {:kind :signal :signal :SIGSYS}\n";
#endif
  static const char unknown[] = "KEXE_TRAP {:kind :signal :signal :unknown}\n";
  const char *message = unknown;
  size_t length = sizeof(unknown) - 1;
#define SELECT_SIGNAL(number, text) \
  case number:                       \
    message = text;                  \
    length = sizeof(text) - 1;       \
    break
  switch (signal_number) {
    SELECT_SIGNAL(SIGILL, sigill);
    SELECT_SIGNAL(SIGTRAP, sigtrap);
    SELECT_SIGNAL(SIGFPE, sigfpe);
    SELECT_SIGNAL(SIGBUS, sigbus);
    SELECT_SIGNAL(SIGSEGV, sigsegv);
    SELECT_SIGNAL(SIGXCPU, sigxcpu);
    SELECT_SIGNAL(SIGALRM, sigalrm);
#if defined(SIGSYS)
    SELECT_SIGNAL(SIGSYS, sigsys);
#endif
    default:
      break;
  }
#undef SELECT_SIGNAL
  ssize_t written = write(STDERR_FILENO, message, length);
  (void)written;
  _exit(120);
}

static void supervisor_alarm_handler(int signal_number) {
  (void)signal_number;
  supervisor_timed_out = 1;
  if (supervised_pid > 0) (void)kill((pid_t)supervised_pid, SIGKILL);
}

static int supervise(pid_t child) {
  struct sigaction action;
  memset(&action, 0, sizeof(action));
  action.sa_handler = supervisor_alarm_handler;
  sigemptyset(&action.sa_mask);
  if (sigaction(SIGALRM, &action, NULL) != 0) fail("supervisor sigaction");
  supervised_pid = (sig_atomic_t)child;
  alarm(3);

  int status = 0;
  while (waitpid(child, &status, 0) < 0) {
    if (errno == EINTR) continue;
    fail("waitpid");
  }
  alarm(0);
  supervised_pid = -1;
  if (supervisor_timed_out) {
    static const char timeout[] =
        "KEXE_TRAP {:kind :supervisor :reason :wall-timeout}\n";
    ssize_t written = write(STDERR_FILENO, timeout, sizeof(timeout) - 1);
    (void)written;
    return 122;
  }
  if (WIFEXITED(status)) return WEXITSTATUS(status);
  static const char signal[] =
      "KEXE_TRAP {:kind :supervisor :reason :unhandled-child-signal}\n";
  ssize_t written = write(STDERR_FILENO, signal, sizeof(signal) - 1);
  (void)written;
  return 123;
}

static void write_supervisor_report(const struct kexe_shared_v2 *shared,
                                    int child_status) {
  if (child_status == 0 && shared->completed == 1) {
    printf("{:status :ok :result %" PRId64
           " :fuel {:initial 256 :remaining %" PRIu64
           "} :heap {:capacity 4096 :used %" PRIu64 "}}\n",
           shared->result, shared->context.fuel, shared->pair_used);
  } else {
    printf("{:status :trap :exit %d :fuel {:initial 256 :remaining %" PRIu64
           "} :heap {:capacity 4096 :used %" PRIu64 "}}\n",
           child_status, shared->context.fuel, shared->pair_used);
  }
}

/* Keep post-sandbox output independent of libc stdio's lazy initialization. */
static void write_i64(int64_t value) {
  char buffer[32];
  size_t cursor = sizeof(buffer);
  uint64_t magnitude;
  buffer[--cursor] = '\n';
  if (value < 0) {
    /* This form is defined for INT64_MIN. */
    magnitude = (uint64_t)(-(value + 1)) + 1;
  } else {
    magnitude = (uint64_t)value;
  }
  do {
    buffer[--cursor] = (char)('0' + magnitude % 10);
    magnitude /= 10;
  } while (magnitude != 0);
  if (value < 0) buffer[--cursor] = '-';

  size_t remaining = sizeof(buffer) - cursor;
  while (remaining != 0) {
    ssize_t written = write(STDOUT_FILENO, buffer + cursor, remaining);
    if (written < 0) {
      if (errno == EINTR) continue;
      _exit(121);
    }
    cursor += (size_t)written;
    remaining -= (size_t)written;
  }
}

static void install_limits(void) {
  struct rlimit limit;
  limit.rlim_cur = limit.rlim_max = 0;
  if (setrlimit(RLIMIT_CORE, &limit) != 0) fail("setrlimit core");
  limit.rlim_cur = 1;
  limit.rlim_max = 2;
  if (setrlimit(RLIMIT_CPU, &limit) != 0) fail("setrlimit cpu");
#if !defined(__APPLE__)
  limit.rlim_cur = limit.rlim_max = 64u * 1024u * 1024u;
  if (setrlimit(RLIMIT_AS, &limit) != 0) fail("setrlimit address-space");
#endif
  limit.rlim_cur = limit.rlim_max = 1024u * 1024u;
  if (setrlimit(RLIMIT_STACK, &limit) != 0) fail("setrlimit stack");

  struct sigaction action;
  memset(&action, 0, sizeof(action));
  action.sa_handler = trap_handler;
  sigemptyset(&action.sa_mask);
  action.sa_flags = SA_RESETHAND;
  const int signals[] = {SIGILL, SIGTRAP, SIGFPE, SIGBUS, SIGSEGV, SIGXCPU, SIGALRM
#if defined(SIGSYS)
                         , SIGSYS
#endif
  };
  for (size_t i = 0; i < sizeof(signals) / sizeof(signals[0]); i++) {
    if (sigaction(signals[i], &action, NULL) != 0) fail("sigaction");
  }
  alarm(2);
}

#if defined(__linux__) && !defined(KEXE_SANITIZER_TEST)
#define ALLOW_SYSCALL(number) \
  BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (number), 0, 1), \
  BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)

static void install_syscall_sandbox(void) {
#if defined(__x86_64__)
  const uint32_t expected_arch = AUDIT_ARCH_X86_64;
#elif defined(__aarch64__)
  const uint32_t expected_arch = AUDIT_ARCH_AARCH64;
#else
#error "unsupported Linux architecture for KEXE seccomp"
#endif
  struct sock_filter filter[] = {
      BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
      BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, expected_arch, 1, 0),
      BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
      BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
      ALLOW_SYSCALL(__NR_write),
      ALLOW_SYSCALL(__NR_exit),
      ALLOW_SYSCALL(__NR_exit_group),
      ALLOW_SYSCALL(__NR_rt_sigreturn),
      ALLOW_SYSCALL(__NR_rt_sigprocmask),
      ALLOW_SYSCALL(__NR_getpid),
      ALLOW_SYSCALL(__NR_gettid),
      ALLOW_SYSCALL(__NR_tgkill),
      ALLOW_SYSCALL(__NR_munmap),
      ALLOW_SYSCALL(__NR_brk),
      BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
  };
  struct sock_fprog program = {
      .len = (unsigned short)(sizeof(filter) / sizeof(filter[0])),
      .filter = filter,
  };
  if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) fail("no_new_privs");
  if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) != 0) fail("seccomp");
}
#elif defined(__APPLE__) && !defined(KEXE_SANITIZER_TEST)
static void install_syscall_sandbox(void) {
  static const char profile[] =
      "(version 1)"
      "(deny default)"
      "(allow file-write-data)"
      "(allow signal (target self))"
      "(allow process-info-pidinfo)"
      "(allow process-info-setcontrol)";
  char *error = NULL;
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
  int result = sandbox_init(profile, 0, &error);
  if (result != 0) {
    if (error != NULL) {
      static const char prefix[] = "kexe-loader: sandbox_init: ";
      (void)write(STDERR_FILENO, prefix, sizeof(prefix) - 1);
      (void)write(STDERR_FILENO, error, strlen(error));
      (void)write(STDERR_FILENO, "\n", 1);
      sandbox_free_error(error);
    }
    _exit(125);
  }
#pragma clang diagnostic pop
}
#else
static void install_syscall_sandbox(void) {}
#endif

static void probe_denied(const char *reason) {
  static const char prefix[] = "KEXE_TRAP {:kind :sandbox :reason :";
  ssize_t written = write(STDERR_FILENO, prefix, sizeof(prefix) - 1);
  written = write(STDERR_FILENO, reason, strlen(reason));
  written = write(STDERR_FILENO, "}\n", 2);
  (void)written;
  _exit(124);
}

int main(int argc, char **argv) {
  if (argc < 6 || argc > 11) {
    fprintf(stderr, "usage: kexe-loader <raw-code> <offset> <arity> <x86_64|aarch64> <allow-csv|-> [i64 ...]\n");
    return 2;
  }
  uint64_t offset;
  if (parse_u64(argv[2], &offset) != 0) return 2;
  unsigned long arity;
  if (parse_ulong_decimal(argv[3], &arity) != 0 || arity > 5 ||
      argc != (int)(6 + arity)) return 2;
  const char *isa = argv[4];
  if (strcmp(isa, "x86_64") != 0 && strcmp(isa, "aarch64") != 0) return 2;
  FILE *file = fopen(argv[1], "rb");
  if (!file) fail("open");
  if (fseek(file, 0, SEEK_END) != 0) fail("seek");
  long length = ftell(file);
  if (length <= 0 || offset >= (uint64_t)length) {
    fprintf(stderr, "kexe-loader: invalid code length or offset\n");
    return 2;
  }
  rewind(file);

  long pagesize = sysconf(_SC_PAGESIZE);
  size_t mapped = ((size_t)length + (size_t)pagesize - 1) & ~((size_t)pagesize - 1);
  void *memory = mmap(NULL, mapped, PROT_READ | PROT_WRITE,
                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (memory == MAP_FAILED) fail("mmap RW");
  if (fread(memory, 1, (size_t)length, file) != (size_t)length) fail("read");
  if (fclose(file) != 0) fail("close");

  /* The security boundary: writable code is never executable. */
  if (mprotect(memory, mapped, PROT_READ | PROT_EXEC) != 0) fail("mprotect RX");
  __builtin___clear_cache((char *)memory, (char *)memory + length);

  int64_t args[6] = {0, 0, 0, 0, 0, 0};
  for (unsigned long i = 0; i < arity; i++) {
    if (parse_i64(argv[6 + i], &args[i]) != 0) return 2;
  }
  struct kexe_shared_v2 *shared =
      mmap(NULL, sizeof(*shared), PROT_READ | PROT_WRITE,
           MAP_SHARED | MAP_ANONYMOUS, -1, 0);
  if (shared == MAP_FAILED) fail("mmap shared execution state");
  memset(shared, 0, sizeof(*shared));
  shared->context.version = 2;
  shared->context.fuel = 256;
  shared->context.cap_call = checked_cap_call;
  shared->context.pair_new = checked_pair_new;
  shared->context.pair_first = checked_pair_first;
  shared->context.pair_second = checked_pair_second;
  shared->context.kgraph_assert = checked_kgraph_assert;
  shared->context.kgraph_get = checked_kgraph_get;
  shared->context.kgraph_count = checked_kgraph_count;
  shared->context.kgraph_entity_at = checked_kgraph_entity_at;
  if (parse_allow(argv[5], shared->context.allow) != 0) return 2;
  int structured_report = getenv("KEXE_STRUCTURED_REPORT") != NULL;

  pid_t child = fork();
  if (child < 0) fail("fork");
  if (child > 0) {
    int child_status = supervise(child);
    if (structured_report) write_supervisor_report(shared, child_status);
    if (munmap(shared, sizeof(*shared)) != 0) fail("supervisor shared munmap");
    if (munmap(memory, mapped) != 0) fail("supervisor munmap");
    return child_status;
  }

  supervised_pid = -1;
  alarm(0);
  if (getenv("KEXE_TIMEOUT_PROBE") != NULL) {
    for (;;) {
    }
  }
  install_limits();
  install_syscall_sandbox();
  if (getenv("KEXE_FILESYSTEM_PROBE") != NULL) {
    int probe = open("/etc/passwd", O_RDONLY);
    if (probe >= 0) {
      (void)close(probe);
      fprintf(stderr, "kexe-loader: filesystem probe unexpectedly succeeded\n");
      return 3;
    }
    probe_denied("filesystem-denied");
  }
  if (getenv("KEXE_NETWORK_PROBE") != NULL) {
    int probe = socket(AF_INET, SOCK_STREAM, 0);
    if (probe < 0) probe_denied("network-denied");
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_port = htons(9);
    address.sin_addr.s_addr = htonl(UINT32_C(0x7f000001));
    errno = 0;
    if (connect(probe, (const struct sockaddr *)&address, sizeof(address)) == 0 ||
        (errno != EPERM && errno != EACCES)) {
      int probe_errno = errno;
      (void)close(probe);
      errno = probe_errno;
      fprintf(stderr, "kexe-loader: network probe was not policy-denied: %s\n",
              strerror(errno));
      return 3;
    }
    (void)close(probe);
    probe_denied("network-denied");
  }
  if (getenv("KEXE_PROCESS_PROBE") != NULL) {
    pid_t probe = fork();
    if (probe == 0) _exit(0);
    if (probe > 0) {
      (void)waitpid(probe, NULL, 0);
      fprintf(stderr, "kexe-loader: process probe unexpectedly succeeded\n");
      return 3;
    }
    probe_denied("process-denied");
  }
  int64_t result;
  if (strcmp(isa, "x86_64") == 0) {
    kexe_fn6 fn = (kexe_fn6)((uint8_t *)memory + offset);
    result = fn(args[0], args[1], args[2], args[3], args[4],
                (int64_t)(uintptr_t)&shared->context);
  } else {
    kexe_fn8 fn = (kexe_fn8)((uint8_t *)memory + offset);
    result = fn(args[0], args[1], args[2], args[3], args[4], 0, 0,
                (int64_t)(uintptr_t)&shared->context);
  }
  shared->result = result;
  shared->completed = 1;
  if (!structured_report) write_i64(result);

  if (munmap(memory, mapped) != 0) fail("munmap");
  if (munmap(shared, sizeof(*shared)) != 0) fail("shared munmap");
  _exit(0);
}
