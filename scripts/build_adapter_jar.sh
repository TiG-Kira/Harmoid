#!/bin/bash
# build_adapter_jar.sh — 从 ArkUI-X 源码编译出 arkui_android_adapter.jar
#
# 背景：
#   app/libs/arkui_android_adapter.jar 是 ohos.stage.ability.adapter.* 的实现，
#   HoaAbilityActivity / HoaApplication 都直接继承它。它不是 Android 侧代码，
#   而是 ArkUI-X 的 Java 适配层，必须从 arkui_for_android 源码编出来。
#   该 jar 不入库（.gitignore 里 app/libs/*.jar），因此需要这个脚本。
#
# 做了什么：
#   1. 浅克隆 harmony-on-android/arkui_for_android 的三个 Java 模块
#        capability/java、entrance/java、stage/ability/java
#   2. 打两处 API 兼容补丁（compileSdk 33+ 才能编译，见下方 PATCHES）
#   3. 下载 fragment 依赖链（StageFragment* 要被 .so 的 JNI FindClass，必须编入）
#   4. javac 到临时目录，打成 jar，丢进 app/libs/
#
# PATCHES（都是框架 API 变更导致的编译问题，不改变行为）：
#   - AceWeb: WebSettings.setAppCacheEnabled/setAppCachePath 自 API 33 移除
#   - AceWeb: MediaMetadataRetriever.release() 自 API 34 声明 throws IOException
# 行为增强补丁（Harmoid 特有，26.10.20 起）：
#   - SubWindowManager: setStatusBar/setNavigationBar 只认 UI_DARK_COLOR /
#     UI_LIGHT_COLOR 两个魔法值，HAP 传任意色值图标明暗不切换 →
#     改为按 contentColor 亮度判定，并同步走 androidx WindowInsetsController
#     （与宿主 edge-to-edge 同管线，避免 legacy flag 被覆盖）。
#
# 用法：
#   ./scripts/build_adapter_jar.sh
#   ./scripts/build_adapter_jar.sh --ref hoa        # 指定分支/提交
#   ./scripts/build_adapter_jar.sh --out app/libs   # 指定输出目录
#
# 依赖：git、JDK 17+、curl、python3、ANDROID_HOME（要 platforms/android-37/android.jar）

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

REPO_URL="${REPO_URL:-https://github.com/harmony-on-android/arkui_for_android.git}"
REF="${REF:-hoa}"
OUT_DIR="$ROOT/app/libs"
ANDROID_JAR="${ANDROID_JAR:-}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

log() { echo "[$(date +%H:%M:%S)] $*"; }
die() { echo "[$(date +%H:%M:%S)] ERROR: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --ref) REF="$2"; shift 2 ;;
        --out) OUT_DIR="$2"; shift 2 ;;
        --src) SRC_DIR="$2"; shift 2 ;;
        --android-jar) ANDROID_JAR="$2"; shift 2 ;;
        -h|--help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "未知参数: $1" ;;
    esac
done

# ---------- 定位 android.jar ----------
if [ -z "$ANDROID_JAR" ]; then
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}"
    ANDROID_JAR="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
fi
[ -f "$ANDROID_JAR" ] || die "找不到 android.jar，请用 --android-jar 指定"
log "android.jar: $ANDROID_JAR"

# ---------- 1. 拉源码 ----------
SRC_DIR="${SRC_DIR:-}"
if [ -n "$SRC_DIR" ]; then
    [ -d "$SRC_DIR/capability/java" ] || die "--src 下找不到 capability/java: $SRC_DIR"
    log "使用已有源码目录: $SRC_DIR"
    cd "$SRC_DIR"
else
    log "克隆 $REPO_URL ($REF)"
    # GitHub 偶发 connection reset，重试三次
    for attempt in 1 2 3; do
        if git clone -q --depth 1 --branch "$REF" --filter=blob:none --sparse \
                "$REPO_URL" "$WORK/src"; then
            break
        fi
        rm -rf "$WORK/src"
        [ "$attempt" -eq 3 ] && die "克隆失败（重试 3 次）"
        log "  克隆失败，第 $attempt 次重试..."
        sleep 3
    done
    cd "$WORK/src"
    git sparse-checkout set capability/java entrance/java stage/ability/java >/dev/null
fi
N=$(find . -name '*.java' | wc -l | tr -d ' ')
log "  源码: $N 个 .java"
[ "$N" -gt 100 ] || die "源码数量异常（$N），sparse-checkout 可能失败"

# ---------- 2. 打 API 兼容补丁 ----------
PY="$(command -v python3 || command -v python)"
command -v "$PY" >/dev/null 2>&1 || die "需要 python3 打补丁"
"$PY" - <<'PY'
import io, sys, glob

p = glob.glob("capability/java/src/**/AceWeb.java", recursive=True)
if not p:
    sys.exit("找不到 AceWeb.java")
p = p[0]
s = io.open(p, encoding="utf-8").read()

# Patch 1: AppCache API 已在 API 33 移除
old1 = """            webSettings.setAppCacheEnabled(true);
            String appCachePath = webView.getContext().getApplicationContext().getDir(
                            "cache", Context.MODE_PRIVATE).getPath();
            webSettings.setAppCachePath(appCachePath);
"""
new1 = """            // [adapter-jar patch] setAppCacheEnabled / setAppCachePath 自 API 33 起已从
            // WebSettings 移除，AppCache 本身也早已废弃，故略去。
"""
# Patch 2: release() 在 API 34 起声明 throws IOException
old2 = """                                } catch (IllegalStateException e) {
                                    ALog.e(LOG_TAG, "Failed to release MediaMetadataRetriever: " + e.getMessage());
                                }"""
new2 = """                                } catch (Exception e) {
                                    // [adapter-jar patch] API 34 起 release() 声明 throws IOException
                                    ALog.e(LOG_TAG, "Failed to release MediaMetadataRetriever: " + e.getMessage());
                                }"""

applied = 0
for old, new in ((old1, new1), (old2, new2)):
    if old in s:
        s = s.replace(old, new)
        applied += 1
if applied == 0:
    print("  补丁无需应用（上游可能已修）")
else:
    print(f"  应用 {applied}/2 处补丁")
io.open(p, "w", encoding="utf-8", newline="\n").write(s)

# ---- Patch 3: SubWindowManager 系统栏图标按任意 contentColor 亮度切换 ----
swm = glob.glob("stage/ability/java/src/**/SubWindowManager.java", recursive=True)
if not swm:
    sys.exit("找不到 SubWindowManager.java")
swm = swm[0]
s = io.open(swm, encoding="utf-8").read()

# 老 setStatusBar：只认两个魔法色值
old_sb = """            if (contentColor == UI_DARK_COLOR) {
                this.uiOptions_ |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; // dark color
            } else if (contentColor == UI_LIGHT_COLOR) {
                this.uiOptions_ &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; // light color
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            decorView.setSystemUiVisibility(this.uiOptions_);
            window.setStatusBarColor(backgroundColor);
            return true;"""
new_sb = """            // [adapter-jar patch] 任意 contentColor 按亮度决定图标明暗：
            // contentColor 深（如黑字）-> 浅色底 -> isAppearanceLightStatusBars = true。
            boolean darkContent = luminance(contentColor) < 0.5f;
            if (contentColor == UI_DARK_COLOR) {
                this.uiOptions_ |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; // dark color
            } else if (contentColor == UI_LIGHT_COLOR) {
                this.uiOptions_ &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; // light color
            } else if (darkContent) {
                this.uiOptions_ |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                this.uiOptions_ &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            decorView.setSystemUiVisibility(this.uiOptions_);
            window.setStatusBarColor(backgroundColor);

            // [adapter-jar patch] 与宿主 edge-to-edge 的 androidx 管线同源设置，
            // 避免 legacy flag 被 WindowInsetsController 的状态覆盖后不生效。
            androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(window, decorView);
            if (controller != null) {
                controller.setAppearanceLightStatusBars(darkContent);
            }
            return true;"""

old_nb = """            if (contentColor == UI_DARK_COLOR) {
                this.uiOptions_ |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; // dark color
            } else if (contentColor == UI_LIGHT_COLOR) {
                this.uiOptions_ &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; // light color
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            decorView.setSystemUiVisibility(this.uiOptions_);
            window.setNavigationBarColor(backgroundColor);
            return true;"""
new_nb = """            // [adapter-jar patch] 与 setStatusBar 相同，任意 contentColor 按亮度切图标明暗。
            boolean darkContent = luminance(contentColor) < 0.5f;
            if (contentColor == UI_DARK_COLOR) {
                this.uiOptions_ |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; // dark color
            } else if (contentColor == UI_LIGHT_COLOR) {
                this.uiOptions_ &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; // light color
            } else if (darkContent) {
                this.uiOptions_ |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                this.uiOptions_ &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            decorView.setSystemUiVisibility(this.uiOptions_);
            window.setNavigationBarColor(backgroundColor);

            androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(window, decorView);
            if (controller != null) {
                controller.setAppearanceLightNavigationBars(darkContent);
            }
            return true;"""

luminance_helper = """
    /**
     * [adapter-jar patch] Compute perceived luminance of an ARGB color (alpha ignored).
     *
     * @param color ARGB color.
     * @return Luminance in [0, 1].
     */
    private static float luminance(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
    }
"""

applied = 0
if old_sb in s:
    s = s.replace(old_sb, new_sb)
    applied += 1
if old_nb in s:
    s = s.replace(old_nb, new_nb)
    applied += 1
# luminance helper：只加一次（幂等）
if "private static float luminance(int color)" not in s and applied > 0:
    anchor = "    /**\n     * Set status bar property."
    if anchor in s:
        s = s.replace(anchor, luminance_helper + anchor, 1)
    else:
        sys.exit("找不到 luminance helper 的插入锚点")
if applied == 0:
    print("  SubWindowManager 补丁无需应用（上游可能已修）")
else:
    print(f"  SubWindowManager 应用 {applied}/2 处补丁")
io.open(swm, "w", encoding="utf-8", newline="\n").write(s)
PY

# ---------- 3. 依赖 ----------
# 源码只依赖少量 androidx 包（其余全是 Android framework）。
# fragment 链是 26.10.19.2 补上的：StageFragment/StageFragmentDelegate 被 libarkui_android.so
# 的 JNI FindClass 直接加载（"Didn't find class StageFragmentDelegate" → StageApplication
# init 失败 → HAP 起不来）。当初为省依赖链排除 StageFragment* 是错的，必须编进 jar。
log "下载 androidx 依赖"
DEPS="$WORK/deps"; mkdir -p "$DEPS"
GOOGLE_MAVEN="https://dl.google.com/dl/android/maven2"
# 格式: group/artifact/version/file   （.aar 解 classes.jar，.jar 直接用）
for a in \
    "androidx/core/core/1.19.0/core-1.19.0.aar" \
    "androidx/window/window/1.5.0/window-1.5.0.aar" \
    "androidx/window/window-java/1.5.0/window-java-1.5.0.aar" \
    "androidx/fragment/fragment/1.5.4/fragment-1.5.4.aar" \
    "androidx/activity/activity/1.9.3/activity-1.9.3.aar" \
    "androidx/lifecycle/lifecycle-common/2.6.2/lifecycle-common-2.6.2.jar" \
    "androidx/lifecycle/lifecycle-runtime/2.6.2/lifecycle-runtime-2.6.2.aar" \
    "androidx/lifecycle/lifecycle-viewmodel/2.6.2/lifecycle-viewmodel-2.6.2.aar" \
    "androidx/savedstate/savedstate/1.2.1/savedstate-1.2.1.aar" \
    "androidx/annotation/annotation-jvm/1.8.1/annotation-jvm-1.8.1.jar" \
    "androidx/collection/collection/1.1.0/collection-1.1.0.jar"
do
    name="$(basename "$a")"
    curl -fsSL -o "$DEPS/$name" "$GOOGLE_MAVEN/$a" || die "下载失败: $a"
    case "$name" in
        *.aar)
            d="${DEPS}/${name%.aar}"; mkdir -p "$d"
            # 个别 aar（如 KMP 化的 lifecycle-viewmodel 2.9.x）没有 classes.jar，
            # unzip 返回非零不能让 set -e 把脚本带崩。
            (cd "$d" && unzip -q -o "$DEPS/$name" classes.jar 2>/dev/null || true)
            [ -f "$d/classes.jar" ] || log "  WARN: $name 无 classes.jar（KMP 包？）"
            ;;
    esac
done

# ---------- 4. 编译 ----------
# StageFragment* 必须编入：libarkui_android.so 的 JNI 会 FindClass 它们，
# 缺类 → StageApplication init 失败 → HAP Activity 起不来（26.10.19.2 实测）。
log "javac 编译（含 StageFragment*）"
find capability/java/src entrance/java/src stage/ability/java/src -name '*.java' \
    > "$WORK/srcs.txt"

CLASSES="$WORK/classes"; mkdir -p "$CLASSES"
to_win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

CP="$(to_win "$ANDROID_JAR")"
for j in "$DEPS"/*/classes.jar "$DEPS"/*.jar; do
    [ -f "$j" ] || continue
    CP="$CP;$(to_win "$j")"
done

javac -nowarn -source 17 -target 17 -encoding UTF-8 \
      -cp "$CP" -d "$(to_win "$CLASSES")" "@$(to_win "$WORK/srcs.txt")" \
    || die "javac 失败"

# ---------- 5. 打包 ----------
mkdir -p "$OUT_DIR"
JAR="$OUT_DIR/arkui_android_adapter.jar"
rm -f "$JAR"
(cd "$CLASSES" && jar cf "$(to_win "$JAR")" .)
log "完成: $JAR ($(stat -c%s "$JAR" 2>/dev/null || stat -f%z "$JAR") 字节, $(find "$CLASSES" -name '*.class' | wc -l | tr -d ' ') 个 class)"
