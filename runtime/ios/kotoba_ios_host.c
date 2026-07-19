#include "kotoba_ios_host.h"

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define KOTOBA_IOS_TARGET "aarch64-ios-kotoba-v1"

extern const unsigned char kotoba_ios_entry[];
extern const char kotoba_ios_target_profile[];

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
  struct kotoba_pair_v1 pairs[KOTOBA_IOS_PAIR_CAPACITY];
};

_Static_assert(offsetof(struct kotoba_context_v2, fuel) == 8, "fuel ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, allow) == 16, "allow ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, cap_call) == 48, "cap ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, pair_new) == 56, "pair ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, pair_first) == 64, "pair ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, pair_second) == 72, "pair ABI drift");

static void guest_trap(void) { __builtin_trap(); }

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
      shared->pair_used >= KOTOBA_IOS_PAIR_CAPACITY) guest_trap();
  uint64_t index = shared->pair_used++;
  shared->pairs[index].first = first;
  shared->pairs[index].second = second;
  return (int64_t)(index + 1);
}

static int64_t pair_get(struct kotoba_context_v2 *context,
                        int64_t handle, int second) {
  struct kotoba_shared_v2 *shared = (struct kotoba_shared_v2 *)context;
  if (context == NULL || context->version != 2 || handle <= 0 ||
      (uint64_t)handle > shared->pair_used) guest_trap();
  const struct kotoba_pair_v1 *pair = &shared->pairs[(uint64_t)handle - 1];
  return second ? pair->second : pair->first;
}

static int64_t pair_first(struct kotoba_context_v2 *context, int64_t handle) {
  return pair_get(context, handle, 0);
}
static int64_t pair_second(struct kotoba_context_v2 *context, int64_t handle) {
  return pair_get(context, handle, 1);
}

#if defined(__APPLE__) && defined(__aarch64__)
typedef int64_t (*kotoba_fn8)(int64_t, int64_t, int64_t, int64_t,
                              int64_t, int64_t, int64_t, int64_t);
#endif

__attribute__((visibility("default")))
int kotoba_ios_execute_static_v1(const struct kotoba_ios_request_v1 *request,
                                 struct kotoba_ios_result_v1 *result) {
  if (result == NULL) return KOTOBA_IOS_INVALID_REQUEST;
  memset(result, 0, sizeof(*result));
  result->abi_version = KOTOBA_IOS_HOST_ABI_V1;
#if !defined(__APPLE__) || !defined(__aarch64__)
  (void)request;
  result->status = KOTOBA_IOS_UNSUPPORTED_HOST;
  return KOTOBA_IOS_UNSUPPORTED_HOST;
#else
  if (request == NULL || request->abi_version != KOTOBA_IOS_HOST_ABI_V1 ||
      request->arity > KOTOBA_IOS_MAX_ARITY || request->target_profile == NULL ||
      strcmp(request->target_profile, KOTOBA_IOS_TARGET) != 0 ||
      strcmp(kotoba_ios_target_profile, KOTOBA_IOS_TARGET) != 0) {
    result->status = KOTOBA_IOS_INVALID_REQUEST;
    return KOTOBA_IOS_INVALID_REQUEST;
  }
  struct kotoba_shared_v2 *shared = calloc(1, sizeof(*shared));
  if (shared == NULL) {
    result->status = KOTOBA_IOS_ALLOCATION_ERROR;
    return KOTOBA_IOS_ALLOCATION_ERROR;
  }
  shared->context.version = 2;
  shared->context.fuel = 512;
  memcpy(shared->context.allow, request->allow, sizeof(request->allow));
  shared->context.cap_call = cap_call;
  shared->context.pair_new = pair_new;
  shared->context.pair_first = pair_first;
  shared->context.pair_second = pair_second;
  kotoba_fn8 entry = (kotoba_fn8)(uintptr_t)kotoba_ios_entry;
  result->value = entry(request->args[0], request->args[1], request->args[2],
                        request->args[3], request->args[4], 0, 0,
                        (int64_t)(intptr_t)&shared->context);
  result->fuel_remaining = shared->context.fuel;
  result->pairs_used = shared->pair_used;
  result->status = KOTOBA_IOS_OK;
  free(shared);
  return KOTOBA_IOS_OK;
#endif
}
