#ifndef KOTOBA_ANDROID_HOST_H
#define KOTOBA_ANDROID_HOST_H

#include <stddef.h>
#include <stdint.h>

#if defined(__cplusplus)
extern "C" {
#endif

#define KOTOBA_ANDROID_HOST_ABI_V1 1u
#define KOTOBA_ANDROID_MAX_CODE_BYTES (1024u * 1024u)
#define KOTOBA_ANDROID_MAX_ARITY 5u
#define KOTOBA_ANDROID_PAIR_CAPACITY 4096u

struct kotoba_android_request_v1 {
  uint32_t abi_version;
  uint32_t arity;
  const char *target_profile;
  const uint8_t *verified_code;
  size_t code_length;
  size_t entry_offset;
  int64_t args[KOTOBA_ANDROID_MAX_ARITY];
  uint64_t allow[4];
};

struct kotoba_android_result_v1 {
  uint32_t abi_version;
  uint32_t status;
  int64_t value;
  uint64_t fuel_remaining;
  uint64_t pairs_used;
};

enum kotoba_android_status_v1 {
  KOTOBA_ANDROID_OK = 0,
  KOTOBA_ANDROID_INVALID_REQUEST = 1,
  KOTOBA_ANDROID_SYSTEM_ERROR = 2,
  KOTOBA_ANDROID_UNSUPPORTED_HOST = 3
};

/*
 * The caller must independently verify and authenticate the KEXE before
 * extracting verified_code. Guest traps intentionally terminate the Android
 * isolated process; they are not converted into in-process C control flow.
 */
int kotoba_android_execute_verified_v1(
    const struct kotoba_android_request_v1 *request,
    struct kotoba_android_result_v1 *result);

#if defined(__cplusplus)
}
#endif

#endif
