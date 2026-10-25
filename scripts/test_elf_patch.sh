#!/bin/sh
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$root/build/native-tests"
cc -std=c11 -Wall -Wextra -Werror -g -fsanitize=address,undefined \
    -Ddlopen=test_dlopen -Ddlclose=test_dlclose -Ddlerror=test_dlerror \
    -I"$root/app/src/test/cpp/include" \
    "$root/app/src/test/cpp/elf_patch_test.c" "$root/app/src/main/cpp/elf_patch.c" \
    -o "$root/build/native-tests/elf_patch_test"
"$root/build/native-tests/elf_patch_test"
