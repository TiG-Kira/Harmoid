#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

// All offsets come from HAP-controlled ELF data. Validate ranges using
// subtraction, so overflowing offsets cannot pass an addition-based check.
typedef struct {
    void *map;
    size_t size;
    Elf64_Dyn *dynamic;
    size_t dynamic_count;
    char *strings;
    size_t strings_size;
} ElfFileView;

static int file_range(size_t size, Elf64_Off offset, Elf64_Xword length)
{
    return offset <= size && length <= size - offset;
}

static int elf_file_open(const char *path, int writable, ElfFileView *view)
{
    memset(view, 0, sizeof(*view));
    int fd = open(path, writable ? O_RDWR : O_RDONLY);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) < 0 || st.st_size < (off_t)sizeof(Elf64_Ehdr) ||
        (uintmax_t)st.st_size > SIZE_MAX) {
        close(fd);
        return -1;
    }
    view->size = (size_t)st.st_size;
    view->map = mmap(NULL, view->size, PROT_READ | (writable ? PROT_WRITE : 0),
                     writable ? MAP_SHARED : MAP_PRIVATE, fd, 0);
    close(fd);
    if (view->map == MAP_FAILED) return -1;

    Elf64_Ehdr *header = view->map;
    if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0 ||
        header->e_ident[EI_CLASS] != ELFCLASS64 ||
        header->e_ident[EI_DATA] != ELFDATA2LSB ||
        header->e_ident[EI_VERSION] != EV_CURRENT ||
        header->e_phentsize != sizeof(Elf64_Phdr) || header->e_phnum == 0 ||
        header->e_phoff % _Alignof(Elf64_Phdr) != 0 ||
        !file_range(view->size, header->e_phoff,
                    (Elf64_Xword)header->e_phnum * sizeof(Elf64_Phdr))) goto fail;

    Elf64_Phdr *segments = (Elf64_Phdr *)((char *)view->map + header->e_phoff);
    for (size_t i = 0; i < header->e_phnum; ++i) {
        Elf64_Phdr *segment = &segments[i];
        if (segment->p_type != PT_DYNAMIC) continue;
        if (!file_range(view->size, segment->p_offset, segment->p_filesz) ||
            segment->p_offset % _Alignof(Elf64_Dyn) != 0 ||
            segment->p_filesz % sizeof(Elf64_Dyn) != 0) goto fail;
        view->dynamic = (Elf64_Dyn *)((char *)view->map + segment->p_offset);
        view->dynamic_count = segment->p_filesz / sizeof(Elf64_Dyn);
        break;
    }
    if (view->dynamic == NULL) goto fail;

    Elf64_Addr string_address = 0;
    Elf64_Xword string_size = 0;
    int have_strings = 0, terminated = 0;
    for (size_t i = 0; i < view->dynamic_count; ++i) {
        Elf64_Dyn *entry = &view->dynamic[i];
        if (entry->d_tag == DT_NULL) {
            view->dynamic_count = i;
            terminated = 1;
            break;
        }
        if (entry->d_tag == DT_STRTAB) {
            string_address = entry->d_un.d_ptr;
            have_strings = 1;
        }
        if (entry->d_tag == DT_STRSZ) string_size = entry->d_un.d_val;
    }
    if (!terminated || !have_strings || string_size == 0) goto fail;

    for (size_t i = 0; i < header->e_phnum; ++i) {
        Elf64_Phdr *segment = &segments[i];
        if (segment->p_type != PT_LOAD || string_address < segment->p_vaddr) continue;
        Elf64_Addr relative = string_address - segment->p_vaddr;
        if (relative >= segment->p_filesz || string_size > segment->p_filesz - relative ||
            !file_range(view->size, segment->p_offset, segment->p_filesz)) continue;
        view->strings = (char *)view->map + segment->p_offset + relative;
        view->strings_size = string_size;
        break;
    }
    if (view->strings == NULL) goto fail;
    // In-place edits must not mutate entries that subsequent iterations read.
    char *dynamic_start = (char *)view->dynamic;
    char *dynamic_end = dynamic_start + (view->dynamic_count + 1) * sizeof(Elf64_Dyn);
    if (view->strings < dynamic_end && view->strings + view->strings_size > dynamic_start) goto fail;
    // Validate every referenced string before any patch is written.
    for (size_t i = 0; i < view->dynamic_count; ++i) {
        Elf64_Dyn *entry = &view->dynamic[i];
        if (entry->d_tag != DT_NEEDED && entry->d_tag != DT_RUNPATH &&
            entry->d_tag != DT_RPATH) continue;
        Elf64_Xword offset = entry->d_un.d_val;
        if (offset >= view->strings_size ||
            memchr(view->strings + offset, '\0', view->strings_size - offset) == NULL) goto fail;
    }
    return 0;

fail:
    munmap(view->map, view->size);
    return -1;
}

// Returns 0 when libc.so was patched or libb.so was already referenced.
int elf_patch_libc_to_libb(const char *path)
{
    ElfFileView view;
    if (elf_file_open(path, 1, &view) != 0) return -1;
    int result = -1;
    for (size_t i = 0; i < view.dynamic_count; ++i) {
        Elf64_Dyn *entry = &view.dynamic[i];
        if (entry->d_tag != DT_NEEDED) continue;
        char *name = view.strings + entry->d_un.d_val;
        if (strcmp(name, "libc.so") == 0) {
            memcpy(name, "libb.so", sizeof("libb.so"));
            result = 0;
        } else if (strcmp(name, "libb.so") == 0) result = 0;
    }
    munmap(view.map, view.size);
    return result;
}

// Only replace paths with enough space, including the NUL terminator.
// Returns -1 when no replaceable RUNPATH/RPATH exists.
int elf_patch_runpath_to_origin(const char *path)
{
    ElfFileView view;
    if (elf_file_open(path, 1, &view) != 0) return -1;
    for (size_t i = 0; i < view.dynamic_count; ++i) {
        Elf64_Dyn *entry = &view.dynamic[i];
        if ((entry->d_tag == DT_RUNPATH || entry->d_tag == DT_RPATH) &&
            strlen(view.strings + entry->d_un.d_val) < sizeof("$ORIGIN") - 1) {
            munmap(view.map, view.size);
            return -1;
        }
    }
    int result = -1;
    for (size_t i = 0; i < view.dynamic_count; ++i) {
        Elf64_Dyn *entry = &view.dynamic[i];
        if (entry->d_tag == DT_RUNPATH || entry->d_tag == DT_RPATH) {
            memcpy(view.strings + entry->d_un.d_val, "$ORIGIN", sizeof("$ORIGIN"));
            result = 0;
        }
    }
    munmap(view.map, view.size);
    return result;
}

#define MAX_NEEDED 32
#define MAX_SONAME 256

int elf_read_needed(const char *path, char (*needed)[MAX_SONAME], int max)
{
    if (needed == NULL || max < 0) return -1;
    ElfFileView view;
    if (elf_file_open(path, 0, &view) != 0) return -1;
    int count = 0;
    for (size_t i = 0; i < view.dynamic_count && count < max; ++i) {
        Elf64_Dyn *entry = &view.dynamic[i];
        if (entry->d_tag != DT_NEEDED) continue;
        const char *name = view.strings + entry->d_un.d_val;
        if (strlen(name) >= MAX_SONAME) {
            munmap(view.map, view.size);
            return -1;
        }
        strcpy(needed[count++], name);
    }
    munmap(view.map, view.size);
    return count;
}

// Load a shared library, recursively loading its DT_NEEDED dependencies
// found in the same directory first. RTLD_NOLOAD skips loaded libraries;
// a separate ancestor chain detects dependencies that are still being visited.
// Returns 0 on success, -1 on failure.
typedef struct LoadFrame {
    const char *path;
    const struct LoadFrame *parent;
} LoadFrame;

static int elf_load_with_deps_inner(const char *path, const LoadFrame *parent, size_t depth)
{
    if (path == NULL || depth >= 128) return -1;
    for (const LoadFrame *frame = parent; frame != NULL; frame = frame->parent) {
        if (strcmp(frame->path, path) == 0) {
            __android_log_print(ANDROID_LOG_WARN, "BRIDGE-LOAD", "Circular dependency: %s", path);
            return -1;
        }
    }
    const LoadFrame current = { path, parent };
    void *check = dlopen(path, RTLD_NOW | RTLD_NOLOAD);
    if (check != NULL) {
        dlclose(check);
        return 0;
    }

    // Keep per-node scratch buffers off the thread stack. Native callers may
    // use small Android pthread stacks even when the dependency graph is deep.
    struct LoadBuffers {
        char needed[MAX_NEEDED][MAX_SONAME];
        char directory[4096];
        char dependency[4096];
    } *buffers = malloc(sizeof(*buffers));
    if (buffers == NULL) return -1;
    int result = -1;
    int count = elf_read_needed(path, buffers->needed, MAX_NEEDED);
    if (count < 0) goto done;

    const char *slash = strrchr(path, '/');
    if (slash != NULL) {
        size_t length = slash - path;
        if (length >= sizeof(buffers->directory)) goto done;
        memcpy(buffers->directory, path, length);
        buffers->directory[length] = '\0';
    } else {
        strcpy(buffers->directory, ".");
    }
    for (int i = 0; i < count; ++i) {
        // Preload siblings only; path-like entries remain the linker's job.
        if (strchr(buffers->needed[i], '/') != NULL) continue;
        int length = snprintf(buffers->dependency, sizeof(buffers->dependency),
                              "%s/%s", buffers->directory, buffers->needed[i]);
        if (length < 0 || (size_t)length >= sizeof(buffers->dependency)) goto done;
        if (access(buffers->dependency, F_OK) == 0) {
            // A cycle can still be handled by dlopen when a valid RUNPATH is
            // present. Stop our traversal, then let the linker try normally.
            elf_load_with_deps_inner(buffers->dependency, &current, depth + 1);
        }
    }

    void *handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        __android_log_print(ANDROID_LOG_WARN, "BRIDGE-LOAD",
                            "dlopen(%s): %s", path, dlerror());
    } else {
        __android_log_print(ANDROID_LOG_INFO, "BRIDGE-LOAD", "dlopen(%s) OK", path);
        result = 0; // Retain the handle for the process-global ArkUI runtime.
    }
done:
    free(buffers);
    return result;
}

int elf_load_with_deps(const char *path)
{
    return elf_load_with_deps_inner(path, NULL, 0);
}
