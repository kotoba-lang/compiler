#include "kotoba_android_host.h"

#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>

static void fail(const char *message) {
  fprintf(stderr, "android-harness: %s\n", message);
  exit(70);
}

static uint64_t parse_u64(const char *text) {
  char *end = NULL;
  errno = 0;
  unsigned long long value = strtoull(text, &end, 10);
  if (errno != 0 || end == text || *end != '\0') fail("invalid integer");
  return (uint64_t)value;
}

int main(int argc, char **argv) {
  if (argc != 4 && argc != 5) {
    fprintf(stderr, "usage: kotoba-android-harness <raw-code> <offset> <arity> [cap-id]\n");
    return 2;
  }
  FILE *input = fopen(argv[1], "rb");
  if (input == NULL) fail("open code");
  if (fseek(input, 0, SEEK_END) != 0) fail("seek code");
  long length = ftell(input);
  if (length <= 0 || (unsigned long)length > KOTOBA_ANDROID_MAX_CODE_BYTES)
    fail("code length");
  rewind(input);
  uint8_t *code = malloc((size_t)length);
  if (code == NULL || fread(code, 1, (size_t)length, input) != (size_t)length)
    fail("read code");
  if (fclose(input) != 0) fail("close code");

  struct kotoba_android_request_v1 request = {0};
  struct kotoba_android_result_v1 result = {0};
  request.abi_version = KOTOBA_ANDROID_HOST_ABI_V1;
  request.arity = (uint32_t)parse_u64(argv[3]);
  request.target_profile = "aarch64-android-kotoba-v1";
  request.verified_code = code;
  request.code_length = (size_t)length;
  request.entry_offset = (size_t)parse_u64(argv[2]);
  if (argc == 5) {
    uint64_t cap_id = parse_u64(argv[4]);
    if (cap_id > 255) fail("capability id");
    request.allow[cap_id / 64] |= UINT64_C(1) << (cap_id % 64);
  }
  int status = kotoba_android_execute_verified_v1(&request, &result);
  free(code);
  if (status != KOTOBA_ANDROID_OK) {
    fprintf(stderr, "android-harness: host status %d\n", status);
    return status;
  }
  printf("{:status :ok :result %" PRId64
         " :fuel {:initial 512 :remaining %" PRIu64 "} :heap {:used %" PRIu64 "}}\n",
         result.value, result.fuel_remaining, result.pairs_used);
  return 0;
}
