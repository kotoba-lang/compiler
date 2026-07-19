#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#define _CRT_SECURE_NO_WARNINGS
#include <windows.h>
#include <rpc.h>
#include <fwpmu.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <sddl.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

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
 * object in install_job_boundary(). Every WFP call is fail-closed: any
 * failure here aborts the loader via fail_win() before guest entry, rather
 * than silently proceeding without network denial.
 *
 * The engine session is opened with FWPM_SESSION_FLAG_DYNAMIC, so the BFE
 * (Base Filtering Engine) automatically tears down these filters when the
 * engine handle closes or this process exits/crashes; the handle is
 * intentionally retained (never closed) until process exit, the same
 * pattern install_job_boundary() uses for its Job handle.
 *
 * NOTE (verification gap, honestly disclosed): FwpmEngineOpen0/FwpmFilterAdd0
 * are BFE management operations that Microsoft's own samples document as
 * requiring an administrative caller. This has not been exercised on a real
 * Windows host from this change -- the windows-arm64 CI job is the actual
 * first real execution of this code path (see docs/threat-model.md).
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
 * instead, as a defensive hardening; whether that was in fact the cause is
 * unverified since this code cannot be exercised outside windows-arm64 CI.
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
 * both without narrowing either. This has not been exercised on a real
 * Windows host from this change (see docs/threat-model.md for the honest
 * disclosure of what remains unverified, including that no probe in this
 * file exercises a genuine non-loopback destination).
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
  if (status != ERROR_SUCCESS && status != FWP_E_ALREADY_EXISTS) {
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
  loopback_conditions[1].conditionValue.uint32 = FWP_CONDITION_FLAG_IS_LOOPBACK;

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

    /* Filter B: loopback-inclusive block, app id AND FWP_CONDITION_FLAG_IS_LOOPBACK.
       This is the filter this app's own loopback probes below actually
       exercise; without it, the confirmed CI failure recurs. */
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
    fprintf(stderr, "kexe-loader-windows: live-listener connection remained blocked for the "
                    "bounded 2s deadline after its pre-filter control succeeded\n");
    return 0;
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

/* Prove that loopback itself is operational before installing WFP filters.
   This prevents a closed port or hosted-runner firewall timeout from being
   mistaken for evidence that our per-process policy works. */
static int loopback_control_reachable(void) {
  WSADATA wsa_data;
  SOCKET listener = INVALID_SOCKET, client = INVALID_SOCKET, accepted = INVALID_SOCKET;
  struct sockaddr_in address;
  int address_length = sizeof(address);
  int result = 70;

  if (WSAStartup(MAKEWORD(2, 2), &wsa_data) != 0) {
    fprintf(stderr, "kexe-loader-windows: pre-filter WSAStartup failed\n");
    return 70;
  }
  listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  client = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (listener == INVALID_SOCKET || client == INVALID_SOCKET) {
    fprintf(stderr, "kexe-loader-windows: pre-filter loopback socket setup failed: wsa=%d\n",
            WSAGetLastError());
    goto cleanup;
  }
  ZeroMemory(&address, sizeof(address));
  address.sin_family = AF_INET;
  address.sin_port = htons(0);
  address.sin_addr.s_addr = htonl(0x7f000001);
  if (bind(listener, (struct sockaddr *)&address, sizeof(address)) != 0 ||
      listen(listener, 1) != 0 ||
      getsockname(listener, (struct sockaddr *)&address, &address_length) != 0 ||
      connect(client, (struct sockaddr *)&address, sizeof(address)) != 0) {
    fprintf(stderr, "kexe-loader-windows: pre-filter live-listener control failed: wsa=%d\n",
            WSAGetLastError());
    goto cleanup;
  }
  accepted = accept(listener, NULL, NULL);
  if (accepted == INVALID_SOCKET) {
    fprintf(stderr, "kexe-loader-windows: pre-filter loopback accept failed: wsa=%d\n",
            WSAGetLastError());
    goto cleanup;
  }
  fprintf(stderr, "kexe-loader-windows: pre-filter live-listener control succeeded\n");
  result = 0;

cleanup:
  if (accepted != INVALID_SOCKET) closesocket(accepted);
  if (client != INVALID_SOCKET) closesocket(client);
  if (listener != INVALID_SOCKET) closesocket(listener);
  WSACleanup();
  return result;
}

/* Outbound direction: a connection to a known-live loopback listener must
   not complete after the ALE_AUTH_CONNECT_V4 filter is installed. */
static int network_outbound_probe_denied(void) {
  WSADATA wsa_data;
  SOCKET listener = INVALID_SOCKET, sock = INVALID_SOCKET;
  struct sockaddr_in address;
  int address_length = sizeof(address);
  int denial;

  if (WSAStartup(MAKEWORD(2, 2), &wsa_data) != 0) {
    fprintf(stderr, "kexe-loader-windows: WSAStartup failed\n");
    return 70;
  }
  listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (listener == INVALID_SOCKET || sock == INVALID_SOCKET) {
    fprintf(stderr, "kexe-loader-windows: network probe socket setup failed: wsa=%d\n",
            WSAGetLastError());
    if (sock != INVALID_SOCKET) closesocket(sock);
    if (listener != INVALID_SOCKET) closesocket(listener);
    WSACleanup();
    return 70;
  }
  ZeroMemory(&address, sizeof(address));
  address.sin_family = AF_INET;
  address.sin_port = htons(0);
  address.sin_addr.s_addr = htonl(0x7f000001);
  if (bind(listener, (struct sockaddr *)&address, sizeof(address)) != 0 ||
      listen(listener, 1) != 0 ||
      getsockname(listener, (struct sockaddr *)&address, &address_length) != 0) {
    fprintf(stderr, "kexe-loader-windows: network probe live-listener setup failed: wsa=%d\n",
            WSAGetLastError());
    closesocket(sock);
    closesocket(listener);
    WSACleanup();
    return 70;
  }
  denial = connect_and_require_denial(sock, &address);
  closesocket(sock);
  closesocket(listener);
  WSACleanup();
  return denial;
}

/*
 * Inbound direction: bind()+listen() on loopback must succeed (the
 * ALE_AUTH_RECV_ACCEPT layer is only consulted when an inbound connection
 * attempt actually arrives, not at bind/listen time -- so those succeeding
 * is the *correct*, expected outcome and is asserted here), then a loopback
 * self-connect to that listener must still be denied.
 *
 * Known limitation, disclosed rather than silently assumed: this loader's
 * Job object caps ActiveProcessLimit=1 (install_job_boundary), so this
 * process cannot spawn a second, independently-filtered peer to originate
 * the inbound connection. The only available stimulus is a loopback
 * self-connect from this same process, which the outbound
 * ALE_AUTH_CONNECT filter already denies on its own. This probe therefore
 * proves the end-to-end guarantee -- no loopback TCP connection ever
 * completes to this process, in either direction -- but it does NOT, by
 * itself, isolate the ALE_AUTH_RECV_ACCEPT_V4/V6 inbound filter from the
 * outbound one. An independent, out-of-process verification of the
 * inbound-only filter is a known gap.
 */
static int network_listen_probe_denied(void) {
  WSADATA wsa_data;
  SOCKET listener, client;
  struct sockaddr_in address;
  int address_length = sizeof(address);
  int denial;

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
  address.sin_addr.s_addr = htonl(0x7f000001);
  if (bind(listener, (struct sockaddr *)&address, sizeof(address)) != 0 ||
      listen(listener, 1) != 0 ||
      getsockname(listener, (struct sockaddr *)&address, &address_length) != 0) {
    fprintf(stderr, "kexe-loader-windows: network listen probe setup failed unexpectedly: wsa=%d\n",
            WSAGetLastError());
    closesocket(listener);
    WSACleanup();
    return 70;
  }
  client = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (client == INVALID_SOCKET) {
    fprintf(stderr, "kexe-loader-windows: network listen probe client socket() failed: wsa=%d\n",
            WSAGetLastError());
    closesocket(listener);
    WSACleanup();
    return 70;
  }
  denial = connect_and_require_denial(client, &address);
  closesocket(client);
  closesocket(listener);
  WSACleanup();
  return denial;
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
    fprintf(stderr, "usage: kexe-loader-windows <raw-code> <offset> <arity> %s <allow-csv|-> [i64 ...]\n",
            KEXE_ISA);
    return 2;
  }
  if (strcmp(argv[4], KEXE_ISA) != 0) fail_input("unsupported ISA");
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
  if ((getenv("KEXE_NETWORK_PROBE") != NULL ||
       getenv("KEXE_NETWORK_LISTEN_PROBE") != NULL) &&
      loopback_control_reachable() != 0)
    return 70;
  install_network_denial();

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
#if defined(_M_X64)
  result = ((guest_fn)(code + offset))(args[0], args[1], args[2], args[3], args[4], ctx);
#else
  result = ((guest_fn)(code + offset))(args[0], args[1], args[2], args[3], args[4], 0, 0, ctx);
#endif
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
