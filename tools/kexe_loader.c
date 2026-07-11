#define _GNU_SOURCE
#include <errno.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

typedef int64_t (*kexe_fn6)(int64_t, int64_t, int64_t, int64_t, int64_t, int64_t);

static void fail(const char *message) {
  fprintf(stderr, "kexe-loader: %s: %s\n", message, strerror(errno));
  exit(1);
}

int main(int argc, char **argv) {
  if (argc < 4 || argc > 10) {
    fprintf(stderr, "usage: kexe-loader <raw-code> <offset> <arity> [i64 ...]\n");
    return 2;
  }
  char *end = NULL;
  uint64_t offset = strtoull(argv[2], &end, 10);
  if (!end || *end) return 2;
  unsigned long arity = strtoul(argv[3], &end, 10);
  if (!end || *end || arity > 6 || argc != (int)(4 + arity)) return 2;

  FILE *file = fopen(argv[1], "rb");
  if (!file) fail("open");
  if (fseek(file, 0, SEEK_END) != 0) fail("seek");
  long length = ftell(file);
  if (length <= 0 || offset >= (uint64_t)length) {
    fprintf(stderr, "kexe-loader: invalid code length or offset\n");
    return 2;
  }
  rewind(file);

  long pagesize = sysconf(_SC_PAGESIZE);
  size_t mapped = ((size_t)length + (size_t)pagesize - 1) & ~((size_t)pagesize - 1);
  void *memory = mmap(NULL, mapped, PROT_READ | PROT_WRITE,
                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (memory == MAP_FAILED) fail("mmap RW");
  if (fread(memory, 1, (size_t)length, file) != (size_t)length) fail("read");
  if (fclose(file) != 0) fail("close");

  /* The security boundary: writable code is never executable. */
  if (mprotect(memory, mapped, PROT_READ | PROT_EXEC) != 0) fail("mprotect RX");
  __builtin___clear_cache((char *)memory, (char *)memory + length);

  int64_t args[6] = {0, 0, 0, 0, 0, 0};
  for (unsigned long i = 0; i < arity; i++) {
    args[i] = strtoll(argv[4 + i], &end, 10);
    if (!end || *end) return 2;
  }
  kexe_fn6 fn = (kexe_fn6)((uint8_t *)memory + offset);
  int64_t result = fn(args[0], args[1], args[2], args[3], args[4], args[5]);
  printf("%" PRId64 "\n", result);

  if (munmap(memory, mapped) != 0) fail("munmap");
  return 0;
}
