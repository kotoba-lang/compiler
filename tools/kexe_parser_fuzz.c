#define _GNU_SOURCE
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* Reuse the production parser implementation in the same translation unit. */
#define main kexe_loader_main_not_used_by_fuzzer
#define KEXE_SANITIZER_TEST 1
#include "kexe_loader.c"
#undef main

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
  if (size > 1024) return 0;
  char *text = (char *)malloc(size + 1);
  if (text == NULL) return 0;
  memcpy(text, data, size);
  text[size] = '\0';

  uint64_t u64 = 0;
  unsigned long ulong_value = 0;
  int64_t i64 = 0;
  uint64_t allow[4] = {0, 0, 0, 0};
  (void)parse_u64(text, &u64);
  (void)parse_ulong_decimal(text, &ulong_value);
  (void)parse_i64(text, &i64);
  (void)parse_allow(text, allow);

  free(text);
  return 0;
}

#if defined(KEXE_STANDALONE_FUZZ)
int main(int argc, char **argv) {
  unsigned long runs = argc == 2 ? strtoul(argv[1], NULL, 10) : 20000;
  uint64_t state = UINT64_C(0x4b4f544f4241465a);
  uint8_t input[1024];
  for (unsigned long run = 0; run < runs; run++) {
    state ^= state << 13;
    state ^= state >> 7;
    state ^= state << 17;
    size_t size = (size_t)(state % sizeof(input));
    for (size_t i = 0; i < size; i++) {
      state ^= state << 13;
      state ^= state >> 7;
      state ^= state << 17;
      input[i] = (uint8_t)state;
    }
    (void)LLVMFuzzerTestOneInput(input, size);
  }
  return 0;
}
#endif
