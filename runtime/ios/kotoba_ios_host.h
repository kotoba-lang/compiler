#ifndef KOTOBA_IOS_HOST_H
#define KOTOBA_IOS_HOST_H

#include <stdint.h>

#if defined(__cplusplus)
extern "C" {
#endif

#define KOTOBA_IOS_HOST_ABI_V1 1u
#define KOTOBA_IOS_MAX_CODE_BYTES (1024u * 1024u)
#define KOTOBA_IOS_MAX_ARITY 5u
#define KOTOBA_IOS_PAIR_CAPACITY 4096u
#define KOTOBA_IOS_STRING_POOL_BYTES 65536u

struct kotoba_ios_request_v1 {
  uint32_t abi_version;
  uint32_t arity;
  const char *target_profile;
  int64_t args[KOTOBA_IOS_MAX_ARITY];
  uint64_t allow[4];
};

struct kotoba_ios_result_v1 {
  uint32_t abi_version;
  uint32_t status;
  int64_t value;
  uint64_t fuel_remaining;
  uint64_t pairs_used;
};

enum kotoba_ios_status_v1 {
  KOTOBA_IOS_OK = 0,
  KOTOBA_IOS_INVALID_REQUEST = 1,
  KOTOBA_IOS_ALLOCATION_ERROR = 2,
  KOTOBA_IOS_UNSUPPORTED_HOST = 3
};

int kotoba_ios_execute_static_v1(const struct kotoba_ios_request_v1 *request,
                                 struct kotoba_ios_result_v1 *result);

#if defined(__cplusplus)
}
#endif

#endif
