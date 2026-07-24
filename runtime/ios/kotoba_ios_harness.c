#include "kotoba_ios_host.h"

#include <inttypes.h>
#include <stdio.h>
#include <string.h>

/* Runs entirely inside the iOS Simulator (xcrun simctl spawn) -- the
   compiled `.kotoba` code (kotoba_ios_code_start/kotoba_ios_entry, linked
   in via program.o) is called directly through the SAME static host
   kotoba_ios_execute_static_v1 (kotoba_ios_host.c) the device build in
   ios-aot-conformance.cljs already exercises statically (Mach-O/symbol
   shape only, never actually run there -- no attached iPhone in CI). This
   harness is what makes an iOS build genuinely EXECUTE, on hardware this
   repo's CI can actually reach: an Apple Silicon GitHub Actions runner's
   Simulator runs arm64 code natively, so kotoba_ios_execute_static_v1's own
   `#if defined(__APPLE__) && defined(__aarch64__)` guard takes the real
   execution path here, not the KOTOBA_IOS_UNSUPPORTED_HOST stub. */

int main(int argc, char **argv) {
  struct kotoba_ios_request_v1 request = {0};
  struct kotoba_ios_result_v1 result = {0};
  if (argc > 2 || (argc == 2 && strcmp(argv[1], "typed") != 0)) {
    fprintf(stderr, "usage: kotoba-ios-simulator-harness [typed]\n");
    return 2;
  }
  request.abi_version = KOTOBA_IOS_HOST_ABI_V1;
  request.arity = 0;
  request.target_profile = "aarch64-ios-kotoba-v1";
  if (argc == 2) request.allow[0] = UINT64_C(1) << 4;
  int status = kotoba_ios_execute_static_v1(&request, &result);
  if (status != KOTOBA_IOS_OK) {
    fprintf(stderr, "ios-harness: host status %d\n", status);
    return status;
  }
  printf("{:status :ok :result %" PRId64
         " :fuel {:initial 512 :remaining %" PRIu64 "} :pairs {:used %" PRIu64 "}}\n",
         result.value, result.fuel_remaining, result.pairs_used);
  return 0;
}
