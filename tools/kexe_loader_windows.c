#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#define _CRT_SECURE_NO_WARNINGS
#include <windows.h>
#include <sddl.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

#if !defined(__clang__) || !defined(_M_X64)
#error "Kotoba Windows loader requires Clang x86-64 sysv_abi support"
#endif

#define SYSV __attribute__((sysv_abi))
#define KEXE_MAX_CODE (1024u * 1024u)
#define KEXE_PAIR_CAPACITY 4096u

struct pair_cell { int64_t first; int64_t second; };
struct kexe_context {
  uint64_t version;
  uint64_t fuel;
  uint8_t allow[32];
  void *cap_call;
  void *pair_new;
  void *pair_first;
  void *pair_second;
  uint64_t pair_used;
  struct pair_cell pairs[KEXE_PAIR_CAPACITY];
};

_Static_assert(offsetof(struct kexe_context, fuel) == 8, "fuel ABI");
_Static_assert(offsetof(struct kexe_context, allow) == 16, "allow ABI");
_Static_assert(offsetof(struct kexe_context, cap_call) == 48, "cap ABI");
_Static_assert(offsetof(struct kexe_context, pair_new) == 56, "pair ABI");
_Static_assert(offsetof(struct kexe_context, pair_first) == 64, "first ABI");
_Static_assert(offsetof(struct kexe_context, pair_second) == 72, "second ABI");

static void fail_win(const char *message) {
  fprintf(stderr, "kexe-loader-windows: %s: win32=%lu\n", message, (unsigned long)GetLastError());
  ExitProcess(70);
}

static void fail_input(const char *message) {
  fprintf(stderr, "kexe-loader-windows: %s\n", message);
  ExitProcess(65);
}

static int64_t SYSV cap_call(struct kexe_context *ctx, uint32_t id, int64_t value) {
  if (ctx == NULL || ctx->version != 2 || id > 255 || !(ctx->allow[id / 8] & (1u << (id % 8))))
    __builtin_trap();
  return value + 1;
}

static struct pair_cell *checked_pair(struct kexe_context *ctx, int64_t handle) {
  if (ctx == NULL || handle <= 0 || (uint64_t)handle > ctx->pair_used) __builtin_trap();
  return &ctx->pairs[(uint64_t)handle - 1];
}

static int64_t SYSV pair_new(struct kexe_context *ctx, int64_t first, int64_t second) {
  uint64_t index;
  if (ctx == NULL || ctx->pair_used >= KEXE_PAIR_CAPACITY) __builtin_trap();
  index = ctx->pair_used++;
  ctx->pairs[index].first = first;
  ctx->pairs[index].second = second;
  return (int64_t)(index + 1);
}

static int64_t SYSV pair_first(struct kexe_context *ctx, int64_t handle) {
  return checked_pair(ctx, handle)->first;
}

static int64_t SYSV pair_second(struct kexe_context *ctx, int64_t handle) {
  return checked_pair(ctx, handle)->second;
}

static uint64_t parse_u64(const char *text, const char *name) {
  char *end = NULL;
  unsigned long long value;
  if (text == NULL || *text == '\0' || *text == '-') fail_input(name);
  errno = 0;
  value = strtoull(text, &end, 10);
  if (errno != 0 || end == text || *end != '\0') fail_input(name);
  return (uint64_t)value;
}

static int64_t parse_i64(const char *text) {
  char *end = NULL;
  long long value;
  if (text == NULL || *text == '\0') fail_input("invalid i64 argument");
  errno = 0;
  value = strtoll(text, &end, 10);
  if (errno != 0 || end == text || *end != '\0') fail_input("invalid i64 argument");
  return (int64_t)value;
}

static void parse_allow(struct kexe_context *ctx, const char *text) {
  const char *cursor = text;
  if (strcmp(text, "-") == 0) return;
  while (*cursor) {
    char *end = NULL;
    unsigned long id;
    errno = 0;
    id = strtoul(cursor, &end, 10);
    if (errno != 0 || end == cursor || id > 255 || (*end != ',' && *end != '\0'))
      fail_input("invalid capability allowlist");
    ctx->allow[id / 8] |= (uint8_t)(1u << (id % 8));
    cursor = *end == ',' ? end + 1 : end;
    if (*end == ',' && *cursor == '\0') fail_input("invalid capability allowlist");
  }
}

static void install_job_boundary(void) {
  HANDLE job = CreateJobObjectW(NULL, NULL);
  JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits;
  if (job == NULL) fail_win("CreateJobObjectW");
  ZeroMemory(&limits, sizeof(limits));
  limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE |
      JOB_OBJECT_LIMIT_ACTIVE_PROCESS;
  limits.BasicLimitInformation.ActiveProcessLimit = 1;
  if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits)))
    fail_win("SetInformationJobObject");
  if (!AssignProcessToJobObject(job, GetCurrentProcess())) fail_win("AssignProcessToJobObject");
  /* Intentionally retain the handle until process exit. */
}

static HANDLE restricted_token(void) {
  HANDLE process_token = NULL, token = NULL, impersonation = NULL;
  PSID low_sid = NULL;
  TOKEN_MANDATORY_LABEL label;
  if (!OpenProcessToken(GetCurrentProcess(), TOKEN_DUPLICATE | TOKEN_QUERY | TOKEN_IMPERSONATE |
                        TOKEN_ADJUST_DEFAULT | TOKEN_ASSIGN_PRIMARY,
                        &process_token)) fail_win("OpenProcessToken");
  if (!CreateRestrictedToken(process_token, DISABLE_MAX_PRIVILEGE | LUA_TOKEN,
                             0, NULL, 0, NULL, 0, NULL, &token))
    fail_win("CreateRestrictedToken");
  CloseHandle(process_token);
  if (!ConvertStringSidToSidW(L"S-1-16-4096", &low_sid)) fail_win("ConvertStringSidToSidW");
  label.Label.Attributes = SE_GROUP_INTEGRITY;
  label.Label.Sid = low_sid;
  if (!SetTokenInformation(token, TokenIntegrityLevel, &label,
                           (DWORD)(sizeof(label) + GetLengthSid(low_sid))))
    fail_win("SetTokenInformation low integrity");
  LocalFree(low_sid);
  if (!DuplicateTokenEx(token, TOKEN_QUERY | TOKEN_IMPERSONATE, NULL,
                        SecurityImpersonation, TokenImpersonation, &impersonation))
    fail_win("DuplicateTokenEx impersonation");
  CloseHandle(token);
  return impersonation;
}

static void prohibit_dynamic_code(void) {
  PROCESS_MITIGATION_DYNAMIC_CODE_POLICY policy;
  ZeroMemory(&policy, sizeof(policy));
  policy.ProhibitDynamicCode = 1;
  if (!SetProcessMitigationPolicy(ProcessDynamicCodePolicy, &policy, sizeof(policy))) {
    ZeroMemory(&policy, sizeof(policy));
    if (!GetProcessMitigationPolicy(GetCurrentProcess(), ProcessDynamicCodePolicy,
                                    &policy, sizeof(policy)) || !policy.ProhibitDynamicCode)
      fail_win("SetProcessMitigationPolicy dynamic code");
  }
}

typedef int64_t (SYSV *guest_fn)(int64_t, int64_t, int64_t, int64_t, int64_t,
                                 struct kexe_context *);

static int sandbox_probe(void) {
  if (getenv("KEXE_FILESYSTEM_PROBE") != NULL) {
    HANDLE file = CreateFileW(L"kotoba-denial-probe.tmp", GENERIC_WRITE, 0, NULL,
                              CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file != INVALID_HANDLE_VALUE) {
      CloseHandle(file);
      DeleteFileW(L"kotoba-denial-probe.tmp");
      fprintf(stderr, "kexe-loader-windows: filesystem probe unexpectedly succeeded\n");
      return 70;
    }
    fprintf(stderr, "KEXE_TRAP {:kind :sandbox :reason :filesystem-denied}\n");
    return 77;
  }
  if (getenv("KEXE_PROCESS_PROBE") != NULL) {
    STARTUPINFOW startup;
    PROCESS_INFORMATION process;
    wchar_t command[] = L"C:\\Windows\\System32\\cmd.exe /c exit 0";
    ZeroMemory(&startup, sizeof(startup));
    ZeroMemory(&process, sizeof(process));
    startup.cb = sizeof(startup);
    if (CreateProcessW(NULL, command, NULL, NULL, FALSE, CREATE_NO_WINDOW,
                       NULL, NULL, &startup, &process)) {
      TerminateProcess(process.hProcess, 70);
      CloseHandle(process.hThread);
      CloseHandle(process.hProcess);
      fprintf(stderr, "kexe-loader-windows: process probe unexpectedly succeeded\n");
      return 70;
    }
    fprintf(stderr, "KEXE_TRAP {:kind :sandbox :reason :process-denied}\n");
    return 77;
  }
  return 0;
}

int main(int argc, char **argv) {
  FILE *file;
  long length = 0;
  size_t read_count;
  uint64_t offset, arity;
  uint8_t *source = NULL, *code = NULL;
  DWORD old_protect = 0;
  struct kexe_context *ctx;
  HANDLE token;
  int64_t args[5] = {0, 0, 0, 0, 0}, result;

  if (argc < 6) {
    fprintf(stderr, "usage: kexe-loader-windows <raw-code> <offset> <arity> x86_64 <allow-csv|-> [i64 ...]\n");
    return 2;
  }
  if (strcmp(argv[4], "x86_64") != 0) fail_input("unsupported ISA");
  offset = parse_u64(argv[2], "invalid offset");
  arity = parse_u64(argv[3], "invalid arity");
  if (arity > 5 || argc != (int)(6 + arity)) fail_input("arity mismatch");
  for (uint64_t i = 0; i < arity; i++) args[i] = parse_i64(argv[6 + i]);

  file = fopen(argv[1], "rb");
  if (file == NULL) fail_input("unable to open code file");
  if (fseek(file, 0, SEEK_END) != 0 || (length = ftell(file)) <= 0 ||
      (unsigned long)length > KEXE_MAX_CODE || offset >= (uint64_t)length)
    fail_input("invalid code length or offset");
  rewind(file);
  source = (uint8_t *)malloc((size_t)length);
  if (source == NULL) fail_input("source allocation failed");
  read_count = fread(source, 1, (size_t)length, file);
  fclose(file);
  if (read_count != (size_t)length) fail_input("short code read");

  SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);
  if (!SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_SYSTEM32)) fail_win("SetDefaultDllDirectories");
  install_job_boundary();
  code = (uint8_t *)VirtualAlloc(NULL, (size_t)length, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
  if (code == NULL) fail_win("VirtualAlloc RW");
  memcpy(code, source, (size_t)length);
  SecureZeroMemory(source, (size_t)length);
  free(source);
  if (!VirtualProtect(code, (size_t)length, PAGE_EXECUTE_READ, &old_protect))
    fail_win("VirtualProtect RX");
  if (!FlushInstructionCache(GetCurrentProcess(), code, (size_t)length))
    fail_win("FlushInstructionCache");
  prohibit_dynamic_code();

  ctx = (struct kexe_context *)VirtualAlloc(NULL, sizeof(*ctx), MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
  if (ctx == NULL) fail_win("VirtualAlloc context");
  ZeroMemory(ctx, sizeof(*ctx));
  ctx->version = 2;
  ctx->fuel = 256;
  ctx->cap_call = (void *)&cap_call;
  ctx->pair_new = (void *)&pair_new;
  ctx->pair_first = (void *)&pair_first;
  ctx->pair_second = (void *)&pair_second;
  parse_allow(ctx, argv[5]);

  token = restricted_token();
  if (!SetThreadToken(NULL, token)) fail_win("SetThreadToken restricted");
  {
    int probe = sandbox_probe();
    if (probe != 0) return probe;
  }
  result = ((guest_fn)(code + offset))(args[0], args[1], args[2], args[3], args[4], ctx);
  if (!SetThreadToken(NULL, NULL)) fail_win("clear thread token");
  CloseHandle(token);

  if (getenv("KEXE_STRUCTURED_REPORT") != NULL)
    printf("{:status :ok :result %lld :fuel {:initial 256 :remaining %llu} :heap {:capacity 4096 :used %llu}}\n",
           (long long)result, (unsigned long long)ctx->fuel, (unsigned long long)ctx->pair_used);
  else printf("%lld\n", (long long)result);

  SecureZeroMemory(ctx, sizeof(*ctx));
  VirtualFree(ctx, 0, MEM_RELEASE);
  VirtualFree(code, 0, MEM_RELEASE);
  return 0;
}
