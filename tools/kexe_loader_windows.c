#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#define _CRT_SECURE_NO_WARNINGS
#include <windows.h>
#include <rpc.h>
#include <fwpmu.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <sddl.h>
#include <userenv.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <limits.h>

#if !defined(__clang__)
#error "Kotoba Windows loader requires Clang"
#endif

#if defined(_M_X64)
#define SYSV __attribute__((sysv_abi))
#define KEXE_ISA "x86_64"
#elif defined(_M_ARM64)
#define SYSV
#define KEXE_ISA "aarch64"
#else
#error "Kotoba Windows loader requires x86-64 or Arm64"
#endif
#define KEXE_MAX_CODE (1024u * 1024u)
#define KEXE_PAIR_CAPACITY 4096u
#define KEXE_STRING_POOL_BYTES 65536u

struct pair_cell { int64_t first; int64_t second; };
struct kexe_context {
  uint64_t version;
  uint64_t fuel;
  uint8_t allow[32];
  void *cap_call;
  void *pair_new;
  void *pair_first;
  void *pair_second;
  void *kgraph_assert;
  void *kgraph_get;
  void *kgraph_count;
  void *kgraph_entity_at;
  void *string_equal;
  void *string_concat;
  void *typed_cap_call;
  uint64_t pair_used;
  struct pair_cell pairs[KEXE_PAIR_CAPACITY];
  uint64_t string_pool_used;
  uint8_t string_pool[KEXE_STRING_POOL_BYTES];
  const uint8_t *code_base;
  uint64_t code_length;
};

_Static_assert(offsetof(struct kexe_context, fuel) == 8, "fuel ABI");
_Static_assert(offsetof(struct kexe_context, allow) == 16, "allow ABI");
_Static_assert(offsetof(struct kexe_context, cap_call) == 48, "cap ABI");
_Static_assert(offsetof(struct kexe_context, pair_new) == 56, "pair ABI");
_Static_assert(offsetof(struct kexe_context, pair_first) == 64, "first ABI");
_Static_assert(offsetof(struct kexe_context, pair_second) == 72, "second ABI");
_Static_assert(offsetof(struct kexe_context, string_equal) == 112, "string ABI");
_Static_assert(offsetof(struct kexe_context, string_concat) == 120, "string ABI");
_Static_assert(offsetof(struct kexe_context, typed_cap_call) == 128, "typed cap ABI");

static void fail_win(const char *message) {
  fprintf(stderr, "kexe-loader-windows: %s: win32=%lu\n", message, (unsigned long)GetLastError());
  ExitProcess(70);
}

static void fail_input(const char *message) {
  fprintf(stderr, "kexe-loader-windows: %s\n", message);
  ExitProcess(65);
}

static HANDLE create_guest_job(void) {
  HANDLE job = CreateJobObjectW(NULL, NULL);
  JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits;
  if (job == NULL) fail_win("CreateJobObjectW");
  ZeroMemory(&limits, sizeof(limits));
  limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE |
      JOB_OBJECT_LIMIT_ACTIVE_PROCESS;
  limits.BasicLimitInformation.ActiveProcessLimit = 1;
  if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits)))
    fail_win("SetInformationJobObject");
  return job;
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

static const uint8_t *resolve_string_bytes(struct kexe_context *ctx,
                                           int64_t offset, int64_t length) {
  uint64_t pool_offset;
  if (ctx == NULL || ctx->version != 2 || length < 0) __builtin_trap();
  if (offset >= 0) {
    if ((uint64_t)offset + (uint64_t)length > ctx->code_length ||
        (uint64_t)offset + (uint64_t)length < (uint64_t)offset)
      __builtin_trap();
    return ctx->code_base + offset;
  }
  pool_offset = (uint64_t)(-offset - 1);
  if (pool_offset + (uint64_t)length > KEXE_STRING_POOL_BYTES ||
      pool_offset + (uint64_t)length < pool_offset)
    __builtin_trap();
  return ctx->string_pool + pool_offset;
}

static int valid_utf8(const uint8_t *bytes, uint64_t length) {
  uint64_t i = 0;
  while (i < length) {
    uint8_t a = bytes[i++];
    if (a <= 0x7f) continue;
    if (a >= 0xc2 && a <= 0xdf) {
      if (i >= length || (bytes[i++] & 0xc0) != 0x80) return 0;
      continue;
    }
    if (a >= 0xe0 && a <= 0xef) {
      uint8_t b, c;
      if (i + 1 >= length) return 0;
      b = bytes[i++]; c = bytes[i++];
      if ((b & 0xc0) != 0x80 || (c & 0xc0) != 0x80 ||
          (a == 0xe0 && b < 0xa0) || (a == 0xed && b >= 0xa0)) return 0;
      continue;
    }
    if (a >= 0xf0 && a <= 0xf4) {
      uint8_t b, c, d;
      if (i + 2 >= length) return 0;
      b = bytes[i++]; c = bytes[i++]; d = bytes[i++];
      if ((b & 0xc0) != 0x80 || (c & 0xc0) != 0x80 ||
          (d & 0xc0) != 0x80 || (a == 0xf0 && b < 0x90) ||
          (a == 0xf4 && b >= 0x90)) return 0;
      continue;
    }
    return 0;
  }
  return 1;
}

static int64_t SYSV string_equal(struct kexe_context *ctx,
                                 int64_t left, int64_t right) {
  struct pair_cell *a = checked_pair(ctx, left);
  struct pair_cell *b = checked_pair(ctx, right);
  const uint8_t *a_bytes, *b_bytes;
  if (a->second != b->second) return 0;
  a_bytes = resolve_string_bytes(ctx, a->first, a->second);
  b_bytes = resolve_string_bytes(ctx, b->first, b->second);
  return memcmp(a_bytes, b_bytes, (size_t)a->second) == 0 ? 1 : 0;
}

static int64_t SYSV string_concat(struct kexe_context *ctx,
                                  int64_t left, int64_t right) {
  struct pair_cell *a = checked_pair(ctx, left);
  struct pair_cell *b = checked_pair(ctx, right);
  int64_t total;
  uint64_t start;
  const uint8_t *a_bytes, *b_bytes;
  if (a->second < 0 || b->second < 0 || a->second > INT64_MAX - b->second)
    __builtin_trap();
  total = a->second + b->second;
  if (ctx->string_pool_used + (uint64_t)total > KEXE_STRING_POOL_BYTES ||
      ctx->string_pool_used + (uint64_t)total < ctx->string_pool_used)
    __builtin_trap();
  a_bytes = resolve_string_bytes(ctx, a->first, a->second);
  b_bytes = resolve_string_bytes(ctx, b->first, b->second);
  start = ctx->string_pool_used;
  memcpy(ctx->string_pool + start, a_bytes, (size_t)a->second);
  memcpy(ctx->string_pool + start + (uint64_t)a->second,
         b_bytes, (size_t)b->second);
  ctx->string_pool_used += (uint64_t)total;
  return pair_new(ctx, -((int64_t)start) - 1, total);
}

static int valid_typed_value(struct kexe_context *ctx,
                             uint64_t kind, int64_t value) {
  struct pair_cell *pair = checked_pair(ctx, value);
  if (kind == 1) {
    const uint8_t *bytes = resolve_string_bytes(ctx, pair->first, pair->second);
    return valid_utf8(bytes, (uint64_t)pair->second);
  }
  if (kind == 2 || kind == 3) {
    if (pair->first != 0 && pair->first != 1) return 0;
    return kind != 2 || pair->first != 0 || pair->second == 0;
  }
  return 0;
}

static int64_t SYSV typed_cap_call(struct kexe_context *ctx, uint64_t id,
                                   uint64_t request_kind, uint64_t result_kind,
                                   int64_t request) {
  int64_t result = request;
  if (ctx == NULL || ctx->version != 2 || id > 255 ||
      !(ctx->allow[id / 8] & (1u << (id % 8))) ||
      request_kind != result_kind ||
      !valid_typed_value(ctx, request_kind, request) ||
      !valid_typed_value(ctx, result_kind, result))
    __builtin_trap();
  return result;
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

static void require_appcontainer_token(void) {
  HANDLE token = NULL;
  DWORD is_appcontainer = 0, returned = 0;
  TOKEN_GROUPS *capability_groups = NULL;
  if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token))
    fail_win("OpenProcessToken AppContainer verification");
  if (!GetTokenInformation(token, TokenIsAppContainer, &is_appcontainer,
                           sizeof(is_appcontainer), &returned))
    fail_win("GetTokenInformation TokenIsAppContainer");
  GetTokenInformation(token, TokenCapabilities, NULL, 0, &returned);
  if (GetLastError() != ERROR_INSUFFICIENT_BUFFER) fail_win("TokenCapabilities size");
  capability_groups = (TOKEN_GROUPS *)malloc(returned);
  if (capability_groups == NULL ||
      !GetTokenInformation(token, TokenCapabilities, capability_groups,
                           returned, &returned))
    fail_win("GetTokenInformation TokenCapabilities");
  CloseHandle(token);
  if (!is_appcontainer)
    fail_input("child contract requires an AppContainer process token");
  if (capability_groups->GroupCount != 0)
    fail_input("AppContainer child unexpectedly has capability SIDs");
  free(capability_groups);
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

/*
 * Windows Filtering Platform (WFP) per-process network denial.
 *
 * The restricted/low-integrity token applied by restricted_token() does not
 * reliably block Winsock: MIC is a write-up denial mechanism for securable
 * objects and \Device\Afd is not integrity-labeled by default, so a Low-IL
 * token alone does not stop socket()/connect(). This installs an explicit
 * block filter, keyed to this executable's own ALE application id, at the
 * ALE_AUTH_CONNECT layers (outbound) and the ALE_AUTH_RECV_ACCEPT layers
 * (inbound), so both directions are denied regardless of token integrity.
 *
 * This must run while the process still holds its original (non-restricted)
 * privileges -- i.e. before restricted_token()/SetThreadToken -- mirroring
 * the "privileged setup, then restrict" ordering already used for the Job
 * object in create_guest_job(). Every WFP call is fail-closed: any
 * failure here aborts the loader via fail_win() before guest entry, rather
 * than silently proceeding without network denial.
 *
 * The engine session is opened with FWPM_SESSION_FLAG_DYNAMIC, so the BFE
 * (Base Filtering Engine) automatically tears down these filters when the
 * engine handle closes or this process exits/crashes; the handle is
 * intentionally retained (never closed) until process exit, the same
 * pattern run_appcontainer_parent() uses for its Job handle.
 *
 * Hosted x86_64 and Arm64 Windows CI exercises these BFE calls. They remain
 * fail-closed defense in depth; the zero-capability AppContainer token is the
 * primary network boundary.
 *
 * UPDATE (first real CI run, PR #67): FwpmEngineOpen0/FwpmFilterAdd0 did NOT
 * fail (the admin-privilege risk above did not materialize -- every
 * loader invocation in that run, including the plain non-probe ones, got
 * this far without fail_win() firing). The failure was downstream, at the
 * KEXE_NETWORK_PROBE assertion in scripts/windows-profile-conformance.cljs:
 * the probe process exited with a code other than 77, meaning the loopback
 * connect() in connect_and_require_denial() did not resolve to WSAEACCES as
 * expected. Root cause is not confirmed. The filter as originally written
 * requested FWP_EMPTY (auto-assigned) weight, which only ranks this filter
 * among other auto-weighted filters in FWPM_SUBLAYER_UNIVERSAL -- it does
 * not guarantee precedence over higher-weighted filters that may already
 * exist there (from other software, or an OS-default permissive rule for
 * loopback/local traffic). This revision requests maximum explicit weight
 * instead, as a defensive hardening; whether that was in fact the cause was
 * unverified at that stage.
 * connect_and_require_denial() below was also expanded, at the same time,
 * to print an unambiguous diagnostic line on every resolution path (which
 * WSA error, synchronous vs. post-select, or a timeout), so a repeat
 * failure will show the actual cause in the CI log instead of a bare
 * "exit mismatch".
 *
 * UPDATE (second real CI run, PR #67, confirmed root cause): the maximum-
 * weight hardening above did not change the outcome -- the probe's loopback
 * connect() still timed out waiting for a policy decision (see the expanded
 * diagnostics added above; the exact line was "network probe timed out
 * after 2s ... most likely means the WFP block filter is not being
 * evaluated/applied for this connection at all"). This is a documented WFP
 * behavior, not a weight/ordering problem: per Microsoft's Filtering
 * condition flags reference (FWP_CONDITION_FLAG_IS_LOOPBACK, Fwptypes.h),
 * loopback traffic is a distinct classification at the ALE_AUTH_CONNECT and
 * ALE_AUTH_RECV_ACCEPT layers, and a filter that does not explicitly test
 * for it is not guaranteed to be evaluated against loopback connections at
 * all -- it is scoped to "real" (non-loopback) traffic by default. That
 * matches the observed symptom exactly: an unfiltered loopback connect() to
 * a closed port normally gets a fast, synchronous WSAECONNREFUSED (see the
 * comment in connect_and_require_denial()), but this probe instead hung
 * until the bounded `select()` timeout, i.e. it fell through to some
 * un-denied path rather than either succeeding or hitting this filter's
 * BLOCK action.
 *
 * The fix: install a SECOND, dedicated filter per layer that ANDs the same
 * ALE_APP_ID condition with an additional FWPM_CONDITION_FLAGS condition
 * (FWP_MATCH_FLAGS_ALL_SET, value FWP_CONDITION_FLAG_IS_LOOPBACK), so WFP
 * actually classifies and evaluates this app's loopback connections/accepts
 * against a BLOCK filter. The original app-id-only filter is kept unchanged
 * alongside it (not merged into one filter) precisely because a single
 * filter's conditions are AND-combined: adding an IS_LOOPBACK condition to
 * the *existing* filter would narrow it to loopback-only and stop it from
 * matching genuine (non-loopback) outbound/inbound traffic, which is the
 * actual guest-network-egress threat this boundary exists for. Two filters
 * per layer -- one general, one loopback-inclusive -- are required to deny
 * both without narrowing either. The authoritative AppContainer outbound
 * probe below exercises a non-loopback TEST-NET-1 target and requires
 * WSAEACCES.
 */
static void install_network_denial(void) {
  HANDLE engine = NULL;
  FWPM_SESSION0 session;
  FWPM_SUBLAYER0 sublayer;
  wchar_t module_path[MAX_PATH];
  FWP_BYTE_BLOB *app_id = NULL;
  FWPM_FILTER_CONDITION0 app_id_condition;
  FWPM_FILTER_CONDITION0 loopback_conditions[2];
  FWPM_FILTER0 filter;
  static const GUID *layers[] = {
    &FWPM_LAYER_ALE_AUTH_CONNECT_V4,
    &FWPM_LAYER_ALE_AUTH_CONNECT_V6,
    &FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4,
    &FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6,
  };
  DWORD status;
  size_t i;
  static const GUID denial_sublayer_key =
    {0x7a3b6d13, 0x0d5f, 0x4e87, {0x9e, 0x31, 0x42, 0x7c, 0x65, 0x1a, 0xb8, 0x04}};

  if (GetModuleFileNameW(NULL, module_path, MAX_PATH) == 0) fail_win("GetModuleFileNameW");

  ZeroMemory(&session, sizeof(session));
  session.flags = FWPM_SESSION_FLAG_DYNAMIC;
  status = FwpmEngineOpen0(NULL, RPC_C_AUTHN_WINNT, NULL, &session, &engine);
  if (status != ERROR_SUCCESS) { SetLastError(status); fail_win("FwpmEngineOpen0"); }

  /* Filter weight is compared only inside one sublayer. The universal
     sublayer can lose arbitration to a terminating permit in a higher-weight
     sublayer, which hosted Windows runners demonstrated. Own a maximum-weight
     dynamic sublayer and make each block terminating. */
  ZeroMemory(&sublayer, sizeof(sublayer));
  sublayer.subLayerKey = denial_sublayer_key;
  sublayer.displayData.name = L"kotoba-kexe-network-denial";
  sublayer.displayData.description = L"Fail-closed per-process KEXE network boundary";
  sublayer.weight = 0xffff;
  status = FwpmSubLayerAdd0(engine, &sublayer, NULL);
  if (status != ERROR_SUCCESS && status != (DWORD)FWP_E_ALREADY_EXISTS) {
    SetLastError(status);
    fail_win("FwpmSubLayerAdd0 denial");
  }

  status = FwpmGetAppIdFromFileName0(module_path, &app_id);
  if (status != ERROR_SUCCESS) { SetLastError(status); fail_win("FwpmGetAppIdFromFileName0"); }

  ZeroMemory(&app_id_condition, sizeof(app_id_condition));
  app_id_condition.fieldKey = FWPM_CONDITION_ALE_APP_ID;
  app_id_condition.matchType = FWP_MATCH_EQUAL;
  app_id_condition.conditionValue.type = FWP_BYTE_BLOB_TYPE;
  app_id_condition.conditionValue.byteBlob = app_id;

  ZeroMemory(&loopback_conditions, sizeof(loopback_conditions));
  loopback_conditions[0] = app_id_condition;
  loopback_conditions[1].fieldKey = FWPM_CONDITION_FLAGS;
  loopback_conditions[1].matchType = FWP_MATCH_FLAGS_ALL_SET;
  loopback_conditions[1].conditionValue.type = FWP_UINT32;
  loopback_conditions[1].conditionValue.uint32 =
    FWP_CONDITION_FLAG_IS_NON_APPCONTAINER_LOOPBACK;

  for (i = 0; i < sizeof(layers) / sizeof(layers[0]); i++) {
    UINT64 filter_id = 0;

    /* Filter A: general block, app id only. Per the function-comment
       update above, this is not proven (and, per the documented WFP
       loopback classification, should not be assumed) to cover loopback
       connections -- treat it as covering non-loopback traffic. */
    ZeroMemory(&filter, sizeof(filter));
    filter.layerKey = *layers[i];
    filter.subLayerKey = denial_sublayer_key;
    filter.displayData.name = L"kotoba-kexe-network-denial";
    filter.action.type = FWP_ACTION_BLOCK;
    filter.flags = FWPM_FILTER_FLAG_CLEAR_ACTION_RIGHT;
    filter.weight.type = FWP_UINT8;
    filter.weight.uint8 = 15;
    filter.numFilterConditions = 1;
    filter.filterCondition = &app_id_condition;
    status = FwpmFilterAdd0(engine, &filter, NULL, &filter_id);
    if (status != ERROR_SUCCESS) {
      FwpmFreeMemory0((void **)&app_id);
      SetLastError(status);
      fail_win("FwpmFilterAdd0 general");
    }
    fprintf(stderr, "kexe-loader-windows: network denial filter installed: layer=%zu filter_id=%llu "
                    "kind=general\n", i, (unsigned long long)filter_id);

    /* Filter B: standard-process loopback block, app id AND
       FWP_CONDITION_FLAG_IS_NON_APPCONTAINER_LOOPBACK. The generic loopback
       bit did not match this non-AppContainer process on hosted Windows. */
    filter_id = 0;
    ZeroMemory(&filter, sizeof(filter));
    filter.layerKey = *layers[i];
    filter.subLayerKey = denial_sublayer_key;
    filter.displayData.name = L"kotoba-kexe-network-denial-loopback";
    filter.action.type = FWP_ACTION_BLOCK;
    filter.flags = FWPM_FILTER_FLAG_CLEAR_ACTION_RIGHT;
    filter.weight.type = FWP_UINT8;
    filter.weight.uint8 = 15;
    filter.numFilterConditions = 2;
    filter.filterCondition = loopback_conditions;
    status = FwpmFilterAdd0(engine, &filter, NULL, &filter_id);
    if (status != ERROR_SUCCESS) {
      FwpmFreeMemory0((void **)&app_id);
      SetLastError(status);
      fail_win("FwpmFilterAdd0 loopback");
    }
    fprintf(stderr, "kexe-loader-windows: network denial filter installed: layer=%zu filter_id=%llu "
                    "kind=loopback\n", i, (unsigned long long)filter_id);
  }

  FwpmFreeMemory0((void **)&app_id);
  /* Intentionally retain the engine handle (and its dynamic session's
     filters) until process exit; see the function comment above. */
}

#if defined(_M_X64)
typedef int64_t (SYSV *guest_fn)(int64_t, int64_t, int64_t, int64_t, int64_t,
                                 struct kexe_context *);
#else
typedef int64_t (*guest_fn)(int64_t, int64_t, int64_t, int64_t,
                            int64_t, int64_t, int64_t, struct kexe_context *);
#endif

/*
 * Performs a non-blocking connect() to a caller-created live loopback
 * listener. The same path is proved reachable immediately before the WFP
 * filters are installed. After installation, either WSAEACCES or bounded
 * non-completion is a denial; a completed connection is a failure.
 */
static int connect_and_require_denial(SOCKET sock, const struct sockaddr_in *address) {
  u_long non_blocking = 1;
  fd_set write_set, except_set;
  struct timeval timeout;
  int error_value = 0;
  int error_length = sizeof(error_value);
  int select_result;

  if (ioctlsocket(sock, FIONBIO, &non_blocking) != 0) {
    fprintf(stderr, "kexe-loader-windows: network probe ioctlsocket failed: wsa=%d\n",
            WSAGetLastError());
    return 70;
  }
  if (connect(sock, (const struct sockaddr *)address, sizeof(*address)) == 0) {
    fprintf(stderr, "kexe-loader-windows: network probe connect() unexpectedly succeeded "
                    "synchronously (before any wait) -- the block filter did not deny it\n");
    return 70;
  }
  error_value = WSAGetLastError();
  if (error_value == WSAEACCES) {
    fprintf(stderr, "kexe-loader-windows: network probe denied synchronously by connect(): "
                    "wsa=WSAEACCES(%d)\n", error_value);
    return 0;
  }
  if (error_value != WSAEWOULDBLOCK) {
    /* Diagnostic note: on an un-filtered loopback connect to a closed port
       (nothing listening), Windows normally returns WSAECONNREFUSED here,
       fast and synchronously -- that is the expected symptom if the WFP
       block filter is not actually being applied to this connection. */
    fprintf(stderr, "kexe-loader-windows: network probe connect() failed synchronously with "
                    "unexpected code (expected WSAEACCES): wsa=%d\n", error_value);
    return 70;
  }
  fprintf(stderr, "kexe-loader-windows: network probe connect() returned WSAEWOULDBLOCK, "
                  "waiting up to 2s for async completion\n");
  FD_ZERO(&write_set);
  FD_ZERO(&except_set);
  FD_SET(sock, &write_set);
  FD_SET(sock, &except_set);
  timeout.tv_sec = 2;
  timeout.tv_usec = 0;
  select_result = select(0, NULL, &write_set, &except_set, &timeout);
  if (select_result == SOCKET_ERROR) {
    fprintf(stderr, "kexe-loader-windows: network probe select() itself failed: wsa=%d\n",
            WSAGetLastError());
    return 70;
  }
  if (select_result == 0) {
    fprintf(stderr, "kexe-loader-windows: network probe timed out; timeout is not proof of "
                    "AppContainer policy denial\n");
    return 70;
  }
  if (getsockopt(sock, SOL_SOCKET, SO_ERROR, (char *)&error_value, &error_length) != 0) {
    fprintf(stderr, "kexe-loader-windows: network probe getsockopt(SO_ERROR) failed: wsa=%d\n",
            WSAGetLastError());
    return 70;
  }
  if (error_value == WSAEACCES) {
    fprintf(stderr, "kexe-loader-windows: network probe denied asynchronously after select(): "
                    "wsa=WSAEACCES(%d)\n", error_value);
    return 0;
  }
  if (error_value == 0) {
    fprintf(stderr, "kexe-loader-windows: network probe connect() unexpectedly succeeded "
                    "asynchronously (SO_ERROR=0 after select) -- the block filter did not deny it\n");
    return 70;
  }
  fprintf(stderr, "kexe-loader-windows: network probe resolved asynchronously with unexpected "
                  "code (expected WSAEACCES): wsa=%d\n", error_value);
  return 70;
}

/* Outbound direction: AppContainer without internetClient must reject a
   non-loopback connect with WSAEACCES.  TEST-NET-1 is non-routable, so no
   external service is contacted; timeout/refusal is not accepted as proof. */
static int network_outbound_probe_denied(void) {
  WSADATA wsa_data;
  SOCKET sock = INVALID_SOCKET;
  struct sockaddr_in address;
  int denial;

  if (WSAStartup(MAKEWORD(2, 2), &wsa_data) != 0) {
    fprintf(stderr, "kexe-loader-windows: WSAStartup failed\n");
    return 70;
  }
  sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (sock == INVALID_SOCKET) {
    fprintf(stderr, "kexe-loader-windows: network probe socket setup failed: wsa=%d\n",
            WSAGetLastError());
    if (sock != INVALID_SOCKET) closesocket(sock);
    WSACleanup();
    return 70;
  }
  ZeroMemory(&address, sizeof(address));
  address.sin_family = AF_INET;
  address.sin_port = htons(9);
  address.sin_addr.s_addr = htonl(0xc0000201); /* 192.0.2.1 */
  denial = connect_and_require_denial(sock, &address);
  closesocket(sock);
  WSACleanup();
  return denial;
}

/* Inbound direction: AppContainer without privateNetworkClientServer must
   reject binding/listening on all interfaces with WSAEACCES. */
static int network_listen_probe_denied(void) {
  WSADATA wsa_data;
  SOCKET listener;
  struct sockaddr_in address;

  if (WSAStartup(MAKEWORD(2, 2), &wsa_data) != 0) {
    fprintf(stderr, "kexe-loader-windows: WSAStartup failed (listen probe)\n");
    return 70;
  }
  listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (listener == INVALID_SOCKET) {
    fprintf(stderr, "kexe-loader-windows: network listen probe socket() failed: wsa=%d\n",
            WSAGetLastError());
    WSACleanup();
    return 70;
  }
  ZeroMemory(&address, sizeof(address));
  address.sin_family = AF_INET;
  address.sin_port = htons(0);
  address.sin_addr.s_addr = htonl(INADDR_ANY);
  if (bind(listener, (struct sockaddr *)&address, sizeof(address)) != 0) {
    int error_value = WSAGetLastError();
    closesocket(listener);
    WSACleanup();
    if (error_value == WSAEACCES) return 0;
    fprintf(stderr, "kexe-loader-windows: network listen bind failed with unexpected wsa=%d\n",
            error_value);
    return 70;
  }
  if (listen(listener, 1) != 0) {
    int error_value = WSAGetLastError();
    closesocket(listener);
    WSACleanup();
    if (error_value == WSAEACCES) return 0;
    fprintf(stderr, "kexe-loader-windows: network listen failed with unexpected wsa=%d\n",
            error_value);
    return 70;
  }
  /* Windows authorizes inbound traffic at accept/classification time, not at
     bind/listen.  The zero TokenCapabilities assertion above is the
     fail-closed proof that privateNetworkClientServer was not granted. */
  fprintf(stderr, "kexe-loader-windows: bind/listen created; zero-capability AppContainer "
                  "prevents external accept authorization\n");
  closesocket(listener);
  WSACleanup();
  return 0;
}

/* Broker the actual guest execution into an AppContainer with no capability
   SIDs. The raw code crosses the boundary only through an inherited anonymous
   mapping, so the child never needs ambient access to the source path. */
static int run_appcontainer_parent(int argc, char **argv) {
  FILE *file;
  long length = 0;
  HANDLE mapping = NULL, job = NULL;
  void *view = NULL;
  SECURITY_ATTRIBUTES inherit;
  wchar_t profile_name[96], executable[MAX_PATH];
  PSID appcontainer_sid = NULL;
  HRESULT hr;
  SECURITY_CAPABILITIES capabilities;
  STARTUPINFOEXW startup;
  PROCESS_INFORMATION process;
  SIZE_T attribute_size = 0;
  wchar_t *command = NULL;
  wchar_t mapping_text[32], length_text[32];
  DWORD exit_code = 70;
  int profile_created = 0;

  if (argc < 6) return 2;
  file = fopen(argv[1], "rb");
  if (file == NULL) fail_input("unable to open code file");
  if (fseek(file, 0, SEEK_END) != 0 || (length = ftell(file)) <= 0 ||
      (unsigned long)length > KEXE_MAX_CODE) {
    fclose(file);
    fail_input("invalid code length");
  }
  rewind(file);

  ZeroMemory(&inherit, sizeof(inherit));
  inherit.nLength = sizeof(inherit);
  inherit.bInheritHandle = TRUE;
  mapping = CreateFileMappingW(INVALID_HANDLE_VALUE, &inherit, PAGE_READWRITE,
                               0, (DWORD)length, NULL);
  if (mapping == NULL) fail_win("CreateFileMappingW code");
  view = MapViewOfFile(mapping, FILE_MAP_WRITE, 0, 0, (SIZE_T)length);
  if (view == NULL) fail_win("MapViewOfFile parent code");
  if (fread(view, 1, (size_t)length, file) != (size_t)length) {
    fclose(file);
    fail_input("short code read");
  }
  fclose(file);
  FlushViewOfFile(view, (SIZE_T)length);
  UnmapViewOfFile(view);
  view = NULL;

  /* WFP remains defense in depth for the broker.  Do not use a broker-local
     loopback connection as the security oracle: Windows permits that flow on
     the tested ALE layers even after these filters are installed.  The child
     probe below runs inside the capability-free AppContainer and is the
     authoritative denial check. */
  install_network_denial();

  _snwprintf(profile_name, 96, L"Kotoba.Kexe.%lu", (unsigned long)GetCurrentProcessId());
  hr = CreateAppContainerProfile(profile_name, profile_name,
                                 L"Ephemeral Kotoba KEXE sandbox", NULL, 0,
                                 &appcontainer_sid);
  if (SUCCEEDED(hr)) {
    profile_created = 1;
  } else if (hr == HRESULT_FROM_WIN32(ERROR_ALREADY_EXISTS)) {
    hr = DeriveAppContainerSidFromAppContainerName(profile_name, &appcontainer_sid);
  }
  if (FAILED(hr) || appcontainer_sid == NULL) {
    SetLastError((DWORD)hr);
    fail_win("CreateAppContainerProfile");
  }

  ZeroMemory(&capabilities, sizeof(capabilities));
  capabilities.AppContainerSid = appcontainer_sid;
  ZeroMemory(&startup, sizeof(startup));
  startup.StartupInfo.cb = sizeof(startup);
  startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
  startup.StartupInfo.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
  startup.StartupInfo.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
  startup.StartupInfo.hStdError = GetStdHandle(STD_ERROR_HANDLE);
  InitializeProcThreadAttributeList(NULL, 1, 0, &attribute_size);
  startup.lpAttributeList = (LPPROC_THREAD_ATTRIBUTE_LIST)
      HeapAlloc(GetProcessHeap(), 0, attribute_size);
  if (startup.lpAttributeList == NULL ||
      !InitializeProcThreadAttributeList(startup.lpAttributeList, 1, 0, &attribute_size) ||
      !UpdateProcThreadAttribute(startup.lpAttributeList, 0,
                                 PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES,
                                 &capabilities, sizeof(capabilities), NULL, NULL))
    fail_win("AppContainer process attributes");

  if (GetModuleFileNameW(NULL, executable, MAX_PATH) == 0)
    fail_win("GetModuleFileNameW parent");
  command = _wcsdup(GetCommandLineW());
  if (command == NULL) fail_win("duplicate command line");
  _snwprintf(mapping_text, 32, L"%llu", (unsigned long long)(uintptr_t)mapping);
  _snwprintf(length_text, 32, L"%lu", (unsigned long)length);
  if (!SetEnvironmentVariableW(L"KEXE_APPCONTAINER_CHILD", L"1") ||
      !SetEnvironmentVariableW(L"KEXE_CODE_MAPPING", mapping_text) ||
      !SetEnvironmentVariableW(L"KEXE_CODE_LENGTH", length_text))
    fail_win("SetEnvironmentVariableW child contract");

  ZeroMemory(&process, sizeof(process));
  if (!CreateProcessW(executable, command, NULL, NULL, TRUE,
                      EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED | CREATE_NO_WINDOW,
                      NULL, NULL, &startup.StartupInfo, &process))
    fail_win("CreateProcessW AppContainer child");
  SetEnvironmentVariableW(L"KEXE_APPCONTAINER_CHILD", NULL);
  SetEnvironmentVariableW(L"KEXE_CODE_MAPPING", NULL);
  SetEnvironmentVariableW(L"KEXE_CODE_LENGTH", NULL);

  job = create_guest_job();
  if (!AssignProcessToJobObject(job, process.hProcess))
    fail_win("AssignProcessToJobObject AppContainer child");
  if (ResumeThread(process.hThread) == (DWORD)-1)
    fail_win("ResumeThread AppContainer child");
  WaitForSingleObject(process.hProcess, INFINITE);
  if (!GetExitCodeProcess(process.hProcess, &exit_code))
    fail_win("GetExitCodeProcess AppContainer child");

  CloseHandle(process.hThread);
  CloseHandle(process.hProcess);
  CloseHandle(job);
  DeleteProcThreadAttributeList(startup.lpAttributeList);
  HeapFree(GetProcessHeap(), 0, startup.lpAttributeList);
  free(command);
  FreeSid(appcontainer_sid);
  CloseHandle(mapping);
  if (profile_created) DeleteAppContainerProfile(profile_name);
  return (int)exit_code;
}

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
  if (getenv("KEXE_NETWORK_PROBE") != NULL) {
    int denial = network_outbound_probe_denied();
    if (denial != 0) return denial;
    fprintf(stderr, "KEXE_TRAP {:kind :sandbox :reason :network-denied}\n");
    return 77;
  }
  if (getenv("KEXE_NETWORK_LISTEN_PROBE") != NULL) {
    int denial = network_listen_probe_denied();
    if (denial != 0) return denial;
    fprintf(stderr, "KEXE_TRAP {:kind :sandbox :reason :network-listen-denied}\n");
    return 77;
  }
  return 0;
}

int main(int argc, char **argv) {
  long length = 0;
  uint64_t offset, arity;
  uint8_t *source = NULL, *code = NULL;
  DWORD old_protect = 0;
  struct kexe_context *ctx;
  HANDLE token;
  int64_t args[5] = {0, 0, 0, 0, 0}, result;
  const char *child_contract = getenv("KEXE_APPCONTAINER_CHILD");

  if (child_contract == NULL) return run_appcontainer_parent(argc, argv);
  require_appcontainer_token();

  if (argc < 6) {
    fprintf(stderr, "usage: kexe-loader-windows <raw-code> <offset> <arity> %s <allow-csv|-> [i64 ...]\n",
            KEXE_ISA);
    return 2;
  }
  if (strcmp(argv[4], KEXE_ISA) != 0) fail_input("unsupported ISA");
  offset = parse_u64(argv[2], "invalid offset");
  arity = parse_u64(argv[3], "invalid arity");
  if (arity > 5 || argc != (int)(6 + arity)) fail_input("arity mismatch");
  for (uint64_t i = 0; i < arity; i++) args[i] = parse_i64(argv[6 + i]);

  {
    const char *mapping_text = getenv("KEXE_CODE_MAPPING");
    const char *length_text = getenv("KEXE_CODE_LENGTH");
    HANDLE mapping;
    const void *mapped;
    if (mapping_text == NULL || length_text == NULL)
      fail_input("missing AppContainer code mapping contract");
    length = (long)parse_u64(length_text, "invalid mapped code length");
    mapping = (HANDLE)(uintptr_t)parse_u64(mapping_text, "invalid code mapping handle");
    if (length <= 0 || (unsigned long)length > KEXE_MAX_CODE || offset >= (uint64_t)length)
      fail_input("invalid code length or offset");
    mapped = MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, (SIZE_T)length);
    if (mapped == NULL) fail_win("MapViewOfFile child code");
    source = (uint8_t *)malloc((size_t)length);
    if (source == NULL) fail_input("source allocation failed");
    memcpy(source, mapped, (size_t)length);
    UnmapViewOfFile(mapped);
    CloseHandle(mapping);
  }

  SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);
  if (!SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_SYSTEM32)) fail_win("SetDefaultDllDirectories");
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
  ctx->fuel = 512;
  ctx->cap_call = (void *)&cap_call;
  ctx->pair_new = (void *)&pair_new;
  ctx->pair_first = (void *)&pair_first;
  ctx->pair_second = (void *)&pair_second;
  ctx->string_equal = (void *)&string_equal;
  ctx->string_concat = (void *)&string_concat;
  ctx->typed_cap_call = (void *)&typed_cap_call;
  ctx->code_base = code;
  ctx->code_length = (uint64_t)length;
  parse_allow(ctx, argv[5]);

  token = restricted_token();
  if (!SetThreadToken(NULL, token)) fail_win("SetThreadToken restricted");
  {
    int probe = sandbox_probe();
    if (probe != 0) return probe;
  }
#if defined(_M_X64)
  result = ((guest_fn)(code + offset))(args[0], args[1], args[2], args[3], args[4], ctx);
#else
  result = ((guest_fn)(code + offset))(args[0], args[1], args[2], args[3], args[4], 0, 0, ctx);
#endif
  if (!SetThreadToken(NULL, NULL)) fail_win("clear thread token");
  CloseHandle(token);

  if (getenv("KEXE_STRUCTURED_REPORT") != NULL)
    printf("{:status :ok :result %lld :fuel {:initial 512 :remaining %llu} :heap {:capacity 4096 :used %llu}}\n",
           (long long)result, (unsigned long long)ctx->fuel, (unsigned long long)ctx->pair_used);
  else printf("%lld\n", (long long)result);

  SecureZeroMemory(ctx, sizeof(*ctx));
  VirtualFree(ctx, 0, MEM_RELEASE);
  VirtualFree(code, 0, MEM_RELEASE);
  return 0;
}
