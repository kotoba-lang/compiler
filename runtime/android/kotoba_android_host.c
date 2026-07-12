#include "kotoba_android_host.h"

#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <unistd.h>

#define KOTOBA_ANDROID_TARGET "aarch64-android-kotoba-v1"

struct kotoba_context_v2 {
  uint64_t version;
  uint64_t fuel;
  uint64_t allow[4];
  int64_t (*cap_call)(struct kotoba_context_v2 *, uint64_t, int64_t);
  int64_t (*pair_new)(struct kotoba_context_v2 *, int64_t, int64_t);
  int64_t (*pair_first)(struct kotoba_context_v2 *, int64_t);
  int64_t (*pair_second)(struct kotoba_context_v2 *, int64_t);
};

struct kotoba_pair_v1 { int64_t first; int64_t second; };

struct kotoba_shared_v2 {
  struct kotoba_context_v2 context;
  uint64_t pair_used;
  struct kotoba_pair_v1 pairs[KOTOBA_ANDROID_PAIR_CAPACITY];
};

_Static_assert(offsetof(struct kotoba_context_v2, fuel) == 8, "fuel ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, allow) == 16, "allow ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, cap_call) == 48, "cap ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, pair_new) == 56, "pair ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, pair_first) == 64, "pair ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, pair_second) == 72, "pair ABI drift");

static void guest_trap(void) {
  __builtin_trap();
}

static int64_t cap_call(struct kotoba_context_v2 *context,
                        uint64_t cap_id, int64_t value) {
  if (context == NULL || context->version != 2 || cap_id > 255 ||
      (context->allow[cap_id / 64] & (UINT64_C(1) << (cap_id % 64))) == 0) {
    guest_trap();
  }
  return value + 1;
}

static int64_t pair_new(struct kotoba_context_v2 *context,
                        int64_t first, int64_t second) {
  struct kotoba_shared_v2 *shared = (struct kotoba_shared_v2 *)context;
  if (context == NULL || context->version != 2 ||
      shared->pair_used >= KOTOBA_ANDROID_PAIR_CAPACITY) {
    guest_trap();
  }
  uint64_t index = shared->pair_used++;
  shared->pairs[index].first = first;
  shared->pairs[index].second = second;
  return (int64_t)(index + 1);
}

static int64_t pair_get(struct kotoba_context_v2 *context,
                        int64_t handle, int second) {
  struct kotoba_shared_v2 *shared = (struct kotoba_shared_v2 *)context;
  if (context == NULL || context->version != 2 || handle <= 0 ||
      (uint64_t)handle > shared->pair_used) {
    guest_trap();
  }
  const struct kotoba_pair_v1 *pair = &shared->pairs[(uint64_t)handle - 1];
  return second ? pair->second : pair->first;
}

static int64_t pair_first(struct kotoba_context_v2 *context, int64_t handle) {
  return pair_get(context, handle, 0);
}

static int64_t pair_second(struct kotoba_context_v2 *context, int64_t handle) {
  return pair_get(context, handle, 1);
}

#if defined(__ANDROID__) && defined(__aarch64__)
typedef int64_t (*kotoba_fn8)(int64_t, int64_t, int64_t, int64_t,
                              int64_t, int64_t, int64_t, int64_t);
#endif

__attribute__((visibility("default")))
int kotoba_android_execute_verified_v1(
    const struct kotoba_android_request_v1 *request,
    struct kotoba_android_result_v1 *result) {
  if (result == NULL) return KOTOBA_ANDROID_INVALID_REQUEST;
  memset(result, 0, sizeof(*result));
  result->abi_version = KOTOBA_ANDROID_HOST_ABI_V1;
#if !defined(__ANDROID__) || !defined(__aarch64__)
  (void)request;
  result->status = KOTOBA_ANDROID_UNSUPPORTED_HOST;
  return KOTOBA_ANDROID_UNSUPPORTED_HOST;
#else
  if (request == NULL || request->abi_version != KOTOBA_ANDROID_HOST_ABI_V1 ||
      request->target_profile == NULL ||
      strcmp(request->target_profile, KOTOBA_ANDROID_TARGET) != 0 ||
      request->verified_code == NULL || request->code_length == 0 ||
      request->code_length > KOTOBA_ANDROID_MAX_CODE_BYTES ||
      request->entry_offset >= request->code_length ||
      request->arity > KOTOBA_ANDROID_MAX_ARITY) {
    result->status = KOTOBA_ANDROID_INVALID_REQUEST;
    return KOTOBA_ANDROID_INVALID_REQUEST;
  }
  long page = sysconf(_SC_PAGESIZE);
  if (page <= 0 || (size_t)page > SIZE_MAX - request->code_length) {
    result->status = KOTOBA_ANDROID_SYSTEM_ERROR;
    return KOTOBA_ANDROID_SYSTEM_ERROR;
  }
  size_t mapped = (request->code_length + (size_t)page - 1) & ~((size_t)page - 1);
  void *memory = mmap(NULL, mapped, PROT_READ | PROT_WRITE,
                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (memory == MAP_FAILED) {
    result->status = KOTOBA_ANDROID_SYSTEM_ERROR;
    return KOTOBA_ANDROID_SYSTEM_ERROR;
  }
  memcpy(memory, request->verified_code, request->code_length);
  __builtin___clear_cache((char *)memory, (char *)memory + request->code_length);
  if (mprotect(memory, mapped, PROT_READ | PROT_EXEC) != 0 ||
      prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
    (void)munmap(memory, mapped);
    result->status = KOTOBA_ANDROID_SYSTEM_ERROR;
    return KOTOBA_ANDROID_SYSTEM_ERROR;
  }
  struct kotoba_shared_v2 *shared = calloc(1, sizeof(*shared));
  if (shared == NULL) {
    (void)munmap(memory, mapped);
    result->status = KOTOBA_ANDROID_SYSTEM_ERROR;
    return KOTOBA_ANDROID_SYSTEM_ERROR;
  }
  shared->context.version = 2;
  shared->context.fuel = 256;
  memcpy(shared->context.allow, request->allow, sizeof(request->allow));
  shared->context.cap_call = cap_call;
  shared->context.pair_new = pair_new;
  shared->context.pair_first = pair_first;
  shared->context.pair_second = pair_second;
  kotoba_fn8 entry = (kotoba_fn8)((uint8_t *)memory + request->entry_offset);
  int64_t value = entry(request->args[0], request->args[1], request->args[2],
                        request->args[3], request->args[4], 0, 0,
                        (int64_t)(intptr_t)&shared->context);
  result->value = value;
  result->fuel_remaining = shared->context.fuel;
  result->pairs_used = shared->pair_used;
  result->status = KOTOBA_ANDROID_OK;
  free(shared);
  if (munmap(memory, mapped) != 0) {
    result->status = KOTOBA_ANDROID_SYSTEM_ERROR;
    return KOTOBA_ANDROID_SYSTEM_ERROR;
  }
  return KOTOBA_ANDROID_OK;
#endif
}
