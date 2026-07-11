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
#include <signal.h>
#include <unistd.h>

#if defined(__linux__)
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#endif

typedef int64_t (*kexe_fn6)(int64_t, int64_t, int64_t, int64_t, int64_t, int64_t);
typedef int64_t (*kexe_fn8)(int64_t, int64_t, int64_t, int64_t,
                            int64_t, int64_t, int64_t, int64_t);

struct kexe_context_v1 {
  uint64_t version;
  uint64_t fuel;
  uint64_t allow[4];
  int64_t (*cap_call)(struct kexe_context_v1 *, uint64_t, int64_t);
};

static int64_t checked_cap_call(struct kexe_context_v1 *context,
                                uint64_t cap_id, int64_t value) {
  if (context == NULL || context->version != 1 || cap_id > 255 ||
      (context->allow[cap_id / 64] & (UINT64_C(1) << (cap_id % 64))) == 0) {
    raise(SIGILL);
    return 0;
  }
  return value + 1;
}

static int parse_allow(const char *text, uint64_t allow[4]) {
  if (strcmp(text, "-") == 0) return 0;
  const char *cursor = text;
  while (*cursor) {
    char *end = NULL;
    unsigned long id = strtoul(cursor, &end, 10);
    if (end == cursor || id > 255 || (*end != ',' && *end != '\0')) return -1;
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
  static const char prefix[] = "KEXE_TRAP signal\n";
  (void)signal_number;
  ssize_t written = write(STDERR_FILENO, prefix, sizeof(prefix) - 1);
  (void)written;
  _exit(120);
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

#if defined(__linux__)
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
#else
static void install_syscall_sandbox(void) {}
#endif

int main(int argc, char **argv) {
  if (argc < 6 || argc > 11) {
    fprintf(stderr, "usage: kexe-loader <raw-code> <offset> <arity> <x86_64|aarch64> <allow-csv|-> [i64 ...]\n");
    return 2;
  }
  char *end = NULL;
  uint64_t offset = strtoull(argv[2], &end, 10);
  if (!end || *end) return 2;
  unsigned long arity = strtoul(argv[3], &end, 10);
  if (!end || *end || arity > 5 || argc != (int)(6 + arity)) return 2;
  const char *isa = argv[4];
  if (strcmp(isa, "x86_64") != 0 && strcmp(isa, "aarch64") != 0) return 2;
  install_limits();

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
    args[i] = strtoll(argv[6 + i], &end, 10);
    if (!end || *end) return 2;
  }
  struct kexe_context_v1 context = {
      .version = 1, .fuel = 256, .allow = {0, 0, 0, 0},
      .cap_call = checked_cap_call};
  if (parse_allow(argv[5], context.allow) != 0) return 2;
  install_syscall_sandbox();
  if (getenv("KEXE_SANDBOX_PROBE") != NULL) {
    int probe = open("/etc/passwd", O_RDONLY);
    if (probe >= 0) {
      (void)close(probe);
      fprintf(stderr, "kexe-loader: sandbox probe unexpectedly opened a file\n");
      return 3;
    }
  }
  int64_t result;
  if (strcmp(isa, "x86_64") == 0) {
    kexe_fn6 fn = (kexe_fn6)((uint8_t *)memory + offset);
    result = fn(args[0], args[1], args[2], args[3], args[4],
                (int64_t)(uintptr_t)&context);
  } else {
    kexe_fn8 fn = (kexe_fn8)((uint8_t *)memory + offset);
    result = fn(args[0], args[1], args[2], args[3], args[4], 0, 0,
                (int64_t)(uintptr_t)&context);
  }
  write_i64(result);

  if (munmap(memory, mapped) != 0) fail("munmap");
  return 0;
}
