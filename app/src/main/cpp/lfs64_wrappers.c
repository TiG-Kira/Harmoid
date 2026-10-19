/*
 * lfs64_wrappers.c — LFS64 entry points for libb.so
 *
 * OHOS HAPs are built against a musl configured with _LARGEFILE64_SOURCE,
 * where fseeko64/ftello64 are weak aliases of fseeko/ftello.  Upstream musl
 * only defines the plain names, and Android's bionic stdio.h declares
 * fseeko64/ftello64 with a conflicting (int) return type, so this file
 * deliberately includes NO headers and uses opaque types.
 *
 * Android LP64 off_t is already 64-bit, so plain forwarding is correct.
 */

extern long fseeko(void *f, long off, int whence);
extern long ftello(void *f);

long fseeko64(void *f, long off, int whence)
{
    return fseeko(f, off, whence);
}

long ftello64(void *f)
{
    return ftello(f);
}
