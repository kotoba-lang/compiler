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
  void *kgraph_assert;
  void *kgraph_get;
  void *kgraph_count;
  void *kgraph_entity_at;
  int64_t (*string_equal)(struct kotoba_context_v2 *, int64_t, int64_t);
  int64_t (*string_concat)(struct kotoba_context_v2 *, int64_t, int64_t);
  int64_t (*typed_cap_call)(struct kotoba_context_v2 *, uint64_t, uint64_t,
                            uint64_t, int64_t);
  const uint8_t *code_base;
  uint64_t code_length;
};

struct kotoba_pair_v1 { int64_t first; int64_t second; };

struct kotoba_shared_v2 {
  struct kotoba_context_v2 context;
  uint64_t pair_used;
  struct kotoba_pair_v1 pairs[KOTOBA_ANDROID_PAIR_CAPACITY];
  uint64_t string_pool_used;
  uint8_t string_pool[KOTOBA_ANDROID_STRING_POOL_BYTES];
};

_Static_assert(offsetof(struct kotoba_context_v2, fuel) == 8, "fuel ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, allow) == 16, "allow ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, cap_call) == 48, "cap ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, pair_new) == 56, "pair ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, pair_first) == 64, "pair ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, pair_second) == 72, "pair ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, string_equal) == 112, "string ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, string_concat) == 120, "string ABI drift");
_Static_assert(offsetof(struct kotoba_context_v2, typed_cap_call) == 128, "typed cap ABI drift");

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

static const uint8_t *resolve_string_bytes(struct kotoba_context_v2 *context,
                                           int64_t offset, int64_t length) {
  struct kotoba_shared_v2 *shared = (struct kotoba_shared_v2 *)context;
  if (context == NULL || context->version != 2 || length < 0) guest_trap();
  if (offset >= 0) {
    if ((uint64_t)offset + (uint64_t)length > context->code_length ||
        (uint64_t)offset + (uint64_t)length < (uint64_t)offset) guest_trap();
    return context->code_base + offset;
  }
  if (offset == INT64_MIN) guest_trap();
  uint64_t pool_offset = (uint64_t)(-offset - 1);
  if (pool_offset + (uint64_t)length > KOTOBA_ANDROID_STRING_POOL_BYTES ||
      pool_offset + (uint64_t)length < pool_offset) guest_trap();
  return shared->string_pool + pool_offset;
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

static int64_t string_equal(struct kotoba_context_v2 *context,
                            int64_t left, int64_t right) {
  int64_t left_length = pair_get(context, left, 1);
  int64_t right_length = pair_get(context, right, 1);
  if (left_length != right_length) return 0;
  const uint8_t *left_bytes =
      resolve_string_bytes(context, pair_get(context, left, 0), left_length);
  const uint8_t *right_bytes =
      resolve_string_bytes(context, pair_get(context, right, 0), right_length);
  return memcmp(left_bytes, right_bytes, (size_t)left_length) == 0 ? 1 : 0;
}

static int64_t string_concat(struct kotoba_context_v2 *context,
                             int64_t left, int64_t right) {
  struct kotoba_shared_v2 *shared = (struct kotoba_shared_v2 *)context;
  int64_t left_length = pair_get(context, left, 1);
  int64_t right_length = pair_get(context, right, 1);
  if (left_length < 0 || right_length < 0 ||
      left_length > INT64_MAX - right_length) guest_trap();
  int64_t total = left_length + right_length;
  if (shared->string_pool_used + (uint64_t)total >
          KOTOBA_ANDROID_STRING_POOL_BYTES ||
      shared->string_pool_used + (uint64_t)total < shared->string_pool_used)
    guest_trap();
  const uint8_t *left_bytes =
      resolve_string_bytes(context, pair_get(context, left, 0), left_length);
  const uint8_t *right_bytes =
      resolve_string_bytes(context, pair_get(context, right, 0), right_length);
  uint64_t start = shared->string_pool_used;
  memcpy(shared->string_pool + start, left_bytes, (size_t)left_length);
  memcpy(shared->string_pool + start + (uint64_t)left_length,
         right_bytes, (size_t)right_length);
  shared->string_pool_used += (uint64_t)total;
  return pair_new(context, -((int64_t)start) - 1, total);
}

static int valid_typed_value(struct kotoba_context_v2 *context,
                             uint64_t kind, int64_t value) {
  int64_t tag_or_offset = pair_get(context, value, 0);
  int64_t payload_or_length = pair_get(context, value, 1);
  if (kind == 1) {
    const uint8_t *bytes =
        resolve_string_bytes(context, tag_or_offset, payload_or_length);
    return valid_utf8(bytes, (uint64_t)payload_or_length);
  }
  if (kind == 2 || kind == 3) {
    if (tag_or_offset != 0 && tag_or_offset != 1) return 0;
    return kind != 2 || tag_or_offset != 0 || payload_or_length == 0;
  }
  return 0;
}

static int64_t typed_cap_call(struct kotoba_context_v2 *context,
                              uint64_t cap_id, uint64_t request_kind,
                              uint64_t result_kind, int64_t request) {
  if (context == NULL || context->version != 2 || cap_id > 255 ||
      (context->allow[cap_id / 64] & (UINT64_C(1) << (cap_id % 64))) == 0 ||
      request_kind != result_kind ||
      !valid_typed_value(context, request_kind, request)) guest_trap();
  int64_t result = request;
  if (!valid_typed_value(context, result_kind, result)) guest_trap();
  return result;
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
  shared->context.fuel = 512;
  memcpy(shared->context.allow, request->allow, sizeof(request->allow));
  shared->context.cap_call = cap_call;
  shared->context.pair_new = pair_new;
  shared->context.pair_first = pair_first;
  shared->context.pair_second = pair_second;
  shared->context.string_equal = string_equal;
  shared->context.string_concat = string_concat;
  shared->context.typed_cap_call = typed_cap_call;
  shared->context.code_base = (const uint8_t *)memory;
  shared->context.code_length = (uint64_t)request->code_length;
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
