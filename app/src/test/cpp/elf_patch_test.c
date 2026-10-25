#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

int elf_patch_libc_to_libb(const char *path);
int elf_patch_runpath_to_origin(const char *path);
int elf_read_needed(const char *path, char (*needed)[256], int max);
int elf_load_with_deps(const char *path);

// Stub only the platform logger and linker. Parsing, mmap, file writes and
// dependency traversal execute the actual production C implementation.
static int load_attempts;
int __android_log_print(int priority, const char *tag, const char *format, ...)
{
    (void)priority; (void)tag; (void)format;
    return 0;
}
void *test_dlopen(const char *path, int flags)
{
    (void)path;
    if (!(flags & RTLD_NOLOAD)) ++load_attempts;
    return NULL;
}
int test_dlclose(void *handle) { (void)handle; return 0; }
char *test_dlerror(void) { return "test linker"; }

static void fixture(unsigned char data[512])
{
    memset(data, 0, 512);
    Elf64_Ehdr *header = (Elf64_Ehdr *)data;
    memcpy(header->e_ident, ELFMAG, SELFMAG);
    header->e_ident[EI_CLASS] = ELFCLASS64;
    header->e_ident[EI_DATA] = ELFDATA2LSB;
    header->e_ident[EI_VERSION] = EV_CURRENT;
    header->e_phoff = sizeof(Elf64_Ehdr);
    header->e_phentsize = sizeof(Elf64_Phdr);
    header->e_phnum = 2;
    Elf64_Phdr *segments = (Elf64_Phdr *)(data + header->e_phoff);
    segments[0].p_type = PT_LOAD;
    segments[0].p_vaddr = 0x1000;
    segments[0].p_filesz = 512;
    segments[1].p_type = PT_DYNAMIC;
    segments[1].p_offset = 256;
    segments[1].p_filesz = 5 * sizeof(Elf64_Dyn);
    Elf64_Dyn *dynamic = (Elf64_Dyn *)(data + 256);
    dynamic[0].d_tag = DT_STRTAB; dynamic[0].d_un.d_ptr = 0x1180;
    dynamic[1].d_tag = DT_STRSZ; dynamic[1].d_un.d_val = 64;
    dynamic[2].d_tag = DT_NEEDED; dynamic[2].d_un.d_val = 1;
    dynamic[3].d_tag = DT_RUNPATH; dynamic[3].d_un.d_val = 32;
    dynamic[4].d_tag = DT_NULL;
    strcpy((char *)data + 385, "libc.so");
    strcpy((char *)data + 416, "/ohos/build/path");
}

static void write_fixture(const char *path, const unsigned char *data, size_t size)
{
    int fd = open(path, O_CREAT | O_TRUNC | O_WRONLY, 0600);
    assert(fd >= 0);
    assert(write(fd, data, size) == (ssize_t)size);
    assert(close(fd) == 0);
}

static void assert_invalid(const char *path, const unsigned char *data, size_t size)
{
    char needed[32][256];
    write_fixture(path, data, size);
    assert(elf_read_needed(path, needed, 32) == -1);
    assert(elf_patch_libc_to_libb(path) == -1);
    assert(elf_patch_runpath_to_origin(path) == -1);
}

int main(int argc, char **argv)
{
    if (argc > 1) {
        char needed[32][256];
        for (int i = 1; i < argc; ++i) {
            if (elf_read_needed(argv[i], needed, 32) < 0) {
                fprintf(stderr, "ELF parser rejected: %s\n", argv[i]);
                return 1;
            }
        }
        printf("Validated %d real shared libraries\n", argc - 1);
        return 0;
    }
    char directory[] = "/tmp/harmoid-elf-XXXXXX";
    assert(mkdtemp(directory) != NULL);
    char path[256], other[256];
    snprintf(path, sizeof(path), "%s/liba.so", directory);
    snprintf(other, sizeof(other), "%s/libb.so", directory);
    _Alignas(Elf64_Ehdr) unsigned char data[512];
    char needed[32][256];

    fixture(data);
    write_fixture(path, data, sizeof(data));
    assert(elf_read_needed(path, needed, 32) == 1);
    assert(strcmp(needed[0], "libc.so") == 0);
    assert(elf_patch_libc_to_libb(path) == 0);
    assert(elf_patch_libc_to_libb(path) == 0);
    assert(elf_read_needed(path, needed, 32) == 1);
    assert(strcmp(needed[0], "libb.so") == 0);
    assert(elf_patch_runpath_to_origin(path) == 0);
    FILE *patched = fopen(path, "rb");
    assert(patched != NULL);
    assert(fread(data, 1, sizeof(data), patched) == sizeof(data));
    fclose(patched);
    assert(strcmp((char *)data + 416, "$ORIGIN") == 0);

    // A short RUNPATH must never overwrite neighboring dynstr entries.
    fixture(data); strcpy((char *)data + 416, "x");
    write_fixture(path, data, sizeof(data));
    assert(elf_patch_runpath_to_origin(path) == -1);

    for (size_t size = 0; size < sizeof(Elf64_Ehdr); ++size) {
        assert_invalid(path, data, size);
    }
    fixture(data); ((Elf64_Ehdr *)data)->e_phoff = UINT64_MAX;
    assert_invalid(path, data, sizeof(data));
    fixture(data); ((Elf64_Ehdr *)data)->e_phoff++;
    assert_invalid(path, data, sizeof(data));
    fixture(data); ((Elf64_Phdr *)(data + 64))[1].p_offset = UINT64_MAX;
    assert_invalid(path, data, sizeof(data));
    fixture(data); ((Elf64_Dyn *)(data + 256))[4].d_tag = DT_SONAME;
    assert_invalid(path, data, sizeof(data));
    fixture(data); ((Elf64_Dyn *)(data + 256))[2].d_un.d_val = UINT64_MAX;
    assert_invalid(path, data, sizeof(data));
    fixture(data); memset(data + 385, 'X', 63);
    assert_invalid(path, data, sizeof(data));
    fixture(data); ((Elf64_Dyn *)(data + 256))[0].d_un.d_ptr = UINT64_MAX;
    assert_invalid(path, data, sizeof(data));
    fixture(data); ((Elf64_Dyn *)(data + 256))[0].d_un.d_ptr = 0x1100;
    assert_invalid(path, data, sizeof(data));

    // Unloaded A -> B -> A must terminate even when every RTLD_NOLOAD fails.
    fixture(data); strcpy((char *)data + 385, "libb.so");
    write_fixture(path, data, sizeof(data));
    fixture(data); strcpy((char *)data + 385, "liba.so");
    write_fixture(other, data, sizeof(data));
    load_attempts = 0;
    assert(elf_load_with_deps(path) == -1);
    assert(load_attempts == 2);
    unlink(path); unlink(other);

    // Deep acyclic graphs stay bounded and keep scratch buffers off the stack.
    for (int i = 0; i < 140; ++i) {
        snprintf(path, sizeof(path), "%s/lib%03d.so", directory, i);
        fixture(data);
        snprintf((char *)data + 385, 31, "lib%03d.so", i + 1);
        write_fixture(path, data, sizeof(data));
    }
    snprintf(path, sizeof(path), "%s/lib000.so", directory);
    load_attempts = 0;
    assert(elf_load_with_deps(path) == -1);
    assert(load_attempts == 128);
    for (int i = 0; i < 140; ++i) {
        snprintf(path, sizeof(path), "%s/lib%03d.so", directory, i);
        unlink(path);
    }
    assert(rmdir(directory) == 0);
    puts("ELF parsing, patching, malformed input, cycle and depth tests passed");
    return 0;
}
