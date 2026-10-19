#!/bin/bash
# pack_prebuilts.sh — 打包 CI 用的 ArkUI-X 预编译产物归档
#
# 背景：
#   HOA 的 APK 需要一个 100GB 级 ArkUI-X 源码构建才产出的运行时
#   （jniLibs/arm64-v8a/*.so、assets/sys/**、app/libs/arkui_android_adapter.jar）。
#   CI 不会重头编译它，而是从 Release 资产 arkui-x-prebuilts-arm64.zip 恢复。
#   本脚本负责生成这个 zip。
#
# 为什么能偷懒：
#   上游发布的 HOA-*.apk 里已经打好了 lib/arm64-v8a/*.so 和 assets/sys/**，
#   直接抽出来就是同一份产物 —— 只有 arkui_android_adapter.jar 不在 APK 里
#   （它被 dex 合并进 classes.dex 了），必须从本地 ArkUI-X 构建产物拿。
#
# 用法：
#   ./scripts/pack_prebuilts.sh                       # 用 Release 里最新的上游 APK 抽 .so/资源
#   ./scripts/pack_prebuilts.sh --apk HOA-26.7.9.1.apk
#   ./scripts/pack_prebuilts.sh --jar /path/to/arkui_android_adapter.jar
#   ./scripts/pack_prebuilts.sh --from-build ~/arkui-x/out/arkui-x/aosp_clang_arm64_release
#
# 产出：
#   ./arkui-x-prebuilts-arm64.zip —— 上传到本仓库 Release（同名），
#   或放到任意直链并把地址写进仓库变量 HOA_PREBUILT_URL。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

UPSTREAM_REPO="${UPSTREAM_REPO:-harmony-on-android/HOA}"
WORK="$(mktemp -d)"
APK=""
JAR=""
FROM_BUILD=""
OUT="$ROOT/arkui-x-prebuilts-arm64.zip"

log()  { echo "[$(date +%H:%M:%S)] $*"; }
die()  { echo "[$(date +%H:%M:%S)] ERROR: $*" >&2; exit 1; }
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

while [ $# -gt 0 ]; do
    case "$1" in
        --apk)        APK="$2"; shift 2 ;;
        --jar)        JAR="$2"; shift 2 ;;
        --from-build) FROM_BUILD="$2"; shift 2 ;;
        --out)        OUT="$2"; shift 2 ;;
        -h|--help)
            sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) die "未知参数: $1" ;;
    esac
done

# ============================================================
# 1. 拿到 APK（本地文件或从上游 Release 下载）
# ============================================================
if [ -z "$APK" ]; then
    command -v gh >/dev/null 2>&1 || die "没有 gh，请用 --apk 指定本地 APK"
    log "从 $UPSTREAM_REPO 下载最新 Release 的 APK"
    APK="$WORK/upstream.apk"
    gh release download --repo "$UPSTREAM_REPO" --pattern "*.apk" --dir "$WORK" --clobber
    APK="$(find "$WORK" -maxdepth 1 -name '*.apk' | head -1)"
    [ -n "$APK" ] || die "没下载到 APK"
fi
[ -f "$APK" ] || die "APK 不存在: $APK"
log "使用 APK: $APK ($(stat -c%s "$APK" 2>/dev/null || stat -f%z "$APK") 字节)"

# ============================================================
# 2. 抽出 jniLibs 与 assets/sys
# ============================================================
# 用 python3 解包而不是 unzip：某些 unzip 发行版（Git Bash / Windows）对
# 「目录通配」匹配会直接报 filename not matched，换成 zipfile 更稳。
JNI_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
SYS_DIR="$ROOT/app/src/main/assets/sys"
mkdir -p "$JNI_DIR" "$SYS_DIR"

command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1 \
    || die "需要 python3 来解包 APK"

log "解包 lib/arm64-v8a/*.so 与 assets/sys/**"
PYTHON_BIN="$(command -v python3 || command -v python)"
"$PYTHON_BIN" - "$APK" "$WORK" <<'PY'
import os, sys, zipfile

apk, work = sys.argv[1], sys.argv[2]
prefixes = ("lib/arm64-v8a/", "assets/sys/")
n = 0
with zipfile.ZipFile(apk) as z:
    for info in z.infolist():
        if info.is_dir() or not info.filename.startswith(prefixes):
            continue
        target = os.path.join(work, info.filename)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with z.open(info) as src, open(target, "wb") as dst:
            dst.write(src.read())
        n += 1
print(f"  extracted {n} entries")
PY

SO_COUNT=$(find "$WORK/lib/arm64-v8a" -name '*.so' 2>/dev/null | wc -l | tr -d ' ')
[ "$SO_COUNT" -gt 0 ] || die "APK 里没有 lib/arm64-v8a/*.so"
cp -f "$WORK"/lib/arm64-v8a/*.so "$JNI_DIR/"
log "  → jniLibs: $SO_COUNT 个 .so"

[ -d "$WORK/assets/sys" ] || die "APK 里没有 assets/sys/"
cp -rf "$WORK"/assets/sys/. "$SYS_DIR"/
log "  → assets/sys: $(find "$SYS_DIR" -type f | wc -l | tr -d ' ') 个文件"

# ============================================================
# 3. adapter jar
# ============================================================
if [ -z "$JAR" ] && [ -n "$FROM_BUILD" ]; then
    JAR="$(find "$FROM_BUILD" -name 'arkui_android_adapter.jar' | head -1)"
fi
mkdir -p "$ROOT/app/libs"
if [ -n "$JAR" ]; then
    [ -f "$JAR" ] || die "jar 不存在: $JAR"
    SRC_REAL="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
    DST_REAL="$(cd "$ROOT/app/libs" && pwd)/arkui_android_adapter.jar"
    if [ "$SRC_REAL" = "$DST_REAL" ]; then
        log "adapter jar 已在目标位置，跳过复制"
    else
        cp -f "$JAR" "$ROOT/app/libs/arkui_android_adapter.jar"
        log "adapter jar ← $JAR"
    fi
elif [ -f "$ROOT/app/libs/arkui_android_adapter.jar" ]; then
    log "adapter jar 已存在于 app/libs/"
else
    die "缺少 arkui_android_adapter.jar。它不在 APK 里（已并入 classes.dex），
     必须从 ArkUI-X 构建产物拿：
       --jar <path>/arkui_android_adapter.jar
     或  --from-build ~/arkui-x/out/arkui-x/aosp_clang_arm64_release
     （ArkUI-X 源码构建见 docs/BUILD.md）"
fi

# ============================================================
# 4. 打 zip
# ============================================================
log "打包 → $OUT"
rm -f "$OUT"
cd "$ROOT"
# 优先用 zip(1)；Windows 的 Git Bash 里通常没有，退回 python 的 zipfile。
if command -v zip >/dev/null 2>&1; then
    zip -q -r "$OUT" \
        app/src/main/jniLibs \
        app/libs \
        app/src/main/assets/sys
else
    "$PYTHON_BIN" - "$OUT" <<'PY'
import os, sys, zipfile

out = sys.argv[1]
roots = ["app/src/main/jniLibs", "app/libs", "app/src/main/assets/sys"]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for root in roots:
        for dirpath, _, files in os.walk(root):
            for name in files:
                path = os.path.join(dirpath, name)
                z.write(path, path.replace(os.sep, "/"))
print(f"  packed {len(zipfile.ZipFile(out).namelist())} entries")
PY
fi

log "完成：$(stat -c%s "$OUT" 2>/dev/null || stat -f%z "$OUT") 字节"
cat <<MSG

下一步（二选一）：
  gh release upload <tag> "$OUT"                  # 传到本仓库 Release
  或把直链写进仓库变量 HOA_PREBUILT_URL

注意：app/libs 里如果只剩 arkui_android_adapter_stub.jar（本地编译用的桩），
      别把它打进 zip —— 真正运行需要完整的 adapter jar。
MSG
