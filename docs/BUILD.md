# Building HOA

HOA 基于 ArkUI-X 构建体系，通过 repo + manifest 管理 8 个定制仓库。

构建流程：

- **一键构建：** `build_all.sh`（需设置 4 个环境变量，见下方）
- **分步构建：** `repo init` → `prebuilts_download.sh` → `build.sh` → `build_musl_bridge.sh` → `sync_arkui_x.sh` → `gradlew assembleDebug`

耗时数小时不等，磁盘需求约 100GB（源码 + 产物）。

---

> **前置参考**: 构建前建议阅读 [ArkUI-X 开发准备](https://gitcode.com/arkui-x/docs/blob/master/zh-cn/framework-dev/quick-start/start-overview.md)，了解基本概念和环境要求。

## 前提条件

### 系统依赖

```bash
sudo apt-get install -y \
    binutils git git-lfs git-core gnupg flex bison gperf \
    build-essential zip curl zlib1g-dev gcc-multilib g++-multilib \
    libc6-dev-i386 lib32ncurses5-dev x11proto-core-dev libx11-dev \
    lib32z-dev ccache libgl1-mesa-dev libxml2-utils xsltproc \
    unzip m4 python3 python3-pip
```

### repo

```bash
curl -s https://raw.gitcode.com/gitcode-dev/repo/raw/main/repo-py3 > /usr/local/bin/repo
chmod a+x /usr/local/bin/repo
```

### Java

AGP 9.1 要求 JDK 17+，推荐 21（CI 用的就是 21）。

```bash
apt install openjdk-21-jdk
export JAVA_HOME=/path/to/jdk
export PATH=${JAVA_HOME}/bin:${PATH}
```

### Android SDK

宿主 App 侧的构建参数（见 `app/build.gradle.kts`）：`compileSdk = 37`、`targetSdk = 36`、`minSdk = 30`、NDK `28.2.13676358`、CMake `3.22.1`、Gradle `9.7.1`。

```bash
./sdkmanager --install "ndk;28.2.13676358" --sdk_root=/path/to/Android/Sdk
./sdkmanager --install "platforms;android-37" --sdk_root=/path/to/Android/Sdk
./sdkmanager --install "build-tools;36.0.0" --sdk_root=/path/to/Android/Sdk
./sdkmanager --install "cmake;3.22.1" --sdk_root=/path/to/Android/Sdk

export ANDROID_HOME=/path/to/Android/Sdk
export PATH=${ANDROID_HOME}/platform-tools:${PATH}
```

HOA 项目根目录创建 `local.properties`（Windows 下注意反斜杠要转义）：

```
sdk.dir=/path/to/Android/Sdk
```

UI 依赖（Miuix、kyant backdrop/shapes）都在 Maven Central，**不需要 GitHub Packages token**。

### musl bridge 额外依赖

构建 libb.so（musl ABI bridge）需要 NDK 28+ 的 aarch64 clang 工具链。libb.so 使 OHOS HAP 的闭源 .so 文件能在 Android bionic 上运行。

```bash
export ANDROID_NDK_HOME=/path/to/ndk/28.2.13676358
export MUSL=/path/to/arkui-x/third_party/musl
export ARKUI_X_SRC=/path/to/arkui-x
export ARKUI_BUILD=$ARKUI_X_SRC/out/arkui-x/aosp_clang_arm64_release
```

> 这四个环境变量是 `build_all.sh` 的必需要求，缺一不可。详见[一键构建](#一键构建-build_allsh)。

---

## 一键构建 (`build_all.sh`)

`scripts/build_all.sh` 一次性完成全部 4 个步骤（ArkUI-X → libb.so → 同步 → APK）：

```bash
export ARKUI_X_SRC=/path/to/arkui-x
export ARKUI_BUILD=$ARKUI_X_SRC/out/arkui-x/aosp_clang_arm64_release
export ANDROID_NDK_HOME=/path/to/ndk/28.2.13676358
export MUSL=$ARKUI_X_SRC/third_party/musl

cd /path/to/HOA
./scripts/build_all.sh
```

支持跳过部分步骤：

```bash
./scripts/build_all.sh --skip-arkui   # 跳过 ArkUI-X 编译
./scripts/build_all.sh --skip-musl    # 跳过 libb.so 编译
```

以下为分步说明，供调试或增量构建参考。

---

## 分步构建

## Step 1: 下载源码

拉取 ArkUI-X 源码树，8 个定制仓库 override 到 `harmony-on-android` 组织的 fork。

```bash
mkdir -p ~/arkui-x-hoa
cd ~/arkui-x-hoa
repo init -u https://gitcode.com/harmony-on-android/manifest \
          -b hoa-weekly -m hoa-weekly.xml --no-repo-verify
repo sync -c -j4
repo forall -c 'git lfs pull'
```

> SSH 方式：`-u git@gitcode.com:harmony-on-android/manifest.git`

---

## Step 2: 下载预编译工具链

下载 Clang、GN、Ninja 等构建工具。

```bash
bash build/prebuilts_download.sh --build-arkuix --skip-ssl
```

---

## Step 3: 编译 ArkUI-X

编译 Android arm64 产物，输出到 `out/arkui-x/aosp_clang_arm64_release/`。

```bash
./build.sh --product-name arkui-x --target-os android
```

> 仅编译 arm64（跳过 arm 32-bit 和 x86_64 模拟器），节省约 60% 时间：
> ```bash
> ./build.sh --product-name arkui-x --target-os android --gn-args 'build_target_arch="arm64"'
> ```

---

## Step 4: 编译 libb.so（musl ABI bridge）

编译 musl 子集为 `libb.so`，使 OHOS HAP 的闭源 .so 能够通过 musl ABI 在 Android bionic 上运行。

```bash
cd /path/to/HOA/app/src/main/cpp
MUSL=$ARKUI_X_SRC/third_party/musl bash build_musl_bridge.sh
```

产物：`app/src/main/jniLibs/arm64-v8a/libb.so`

> libb.so 需要 NDK 28+ 的 aarch64 clang，与 APK 的 NDK 21 是两套独立工具链。
> `build_musl_bridge.sh` 会检查 `ANDROID_NDK_HOME` 和 `MUSL` 两个环境变量。

---

## Step 5: 同步产物到 HOA

将 ArkUI-X 构建的 .so、资源文件、jar 等复制到 HOA 项目对应目录。

```bash
cd /path/to/HOA
ARKUI_BUILD=~/arkui-x-hoa/out/arkui-x/aosp_clang_arm64_release ./scripts/sync_arkui_x.sh
```

增量构建（仅更新 .so）：

```bash
./scripts/sync_arkui_x.sh --so-only
```

---

## Step 6: 构建 APK

前提是 Step 5 已经把 ArkUI-X 产物同步进来，`app/src/main/jniLibs/arm64-v8a/libarkui_android.so` 与 `app/libs/arkui_android_adapter.jar` 必须存在——CMake 里的 `-larkui_android` 直接以它为链接目标，缺了会在 `ninja` 阶段报 `unable to find library -larkui_android`。

```bash
./gradlew assembleDebug
```

产物: `app/build/outputs/apk/debug/app-debug.apk` (~85MB)

Release（需要签名材料，见下）：

```bash
export KI_TERMINAL_STORE_PASSWORD=...
export KI_TERMINAL_KEY_PASSWORD=...
export KI_TERMINAL_KEY_ALIAS=...      # 可选，默认 ki-terminal
./gradlew assembleRelease
```

---

## CI 与预编译产物

`.github/workflows/build_apk.yml` / `release_apk.yml` 不会重头编译 ArkUI-X，它们通过 `.github/actions/prepare-arkui-x` 恢复产物：

1. 先查 Actions 缓存（key 由 `scripts/sync_arkui_x.sh` 与 `manifests/hoa-weekly.xml` 的哈希决定）；
2. 未命中则从本仓库 Release 下载 `arkui-x-prebuilts-arm64.zip`，或从仓库变量 `HOA_PREBUILT_URL` 指定的地址拉取；
3. 解压后校验 `libarkui_android.so` / `arkui_android_adapter.jar` / `assets/sys/systemres` 三件套；
4. 校验通过后回写缓存。

制包方法：`scripts/pack_prebuilts.sh` 已经把这条路径固化了。

```bash
# 从上游 Release 的 APK 里抽出 .so 和 assets/sys（这部分 APK 里就有），
# adapter jar 必须另外给（它被 dex 合并进 classes.dex 了，抽不出来）
./scripts/pack_prebuilts.sh --jar <path>/arkui_android_adapter.jar

# 或者整份产物都来自本地 ArkUI-X 构建
./scripts/pack_prebuilts.sh --from-build ~/arkui-x/out/arkui-x/aosp_clang_arm64_release
```

产出 `arkui-x-prebuilts-arm64.zip`，传到本仓库 Release（文件名保持一致），或把直链写进仓库变量 `HOA_PREBUILT_URL`。

`--jar` 的来源是 ArkUI-X 构建产物里的 `arkui_android_adapter.jar`（`stage/` 或 `adapter/` 目录下）。这是唯一必须自己构建才能拿到的东西，其余都能从上游 APK 里抽。

签名 Secrets 与 KiTerminal-UX 同名同义：`JKS_BASE64`、`KI_TERMINAL_STORE_PASSWORD`、`KI_TERMINAL_KEY_PASSWORD`、`KI_TERMINAL_KEY_ALIAS`。私钥不入库（`*.jks` 已在 `.gitignore` 里）。

---

## 常见工作流

### 完整重建

```bash
# 推荐：使用一键构建脚本
cd /path/to/HOA
ARKUI_X_SRC=/path/to/arkui-x \
ARKUI_BUILD=/path/to/arkui-x/out/arkui-x/aosp_clang_arm64_release \
ANDROID_NDK_HOME=/path/to/ndk \
MUSL=/path/to/arkui-x/third_party/musl \
./scripts/build_all.sh

# 或者分步：
cd ~/arkui-x-hoa
./build.sh --product-name arkui-x --target-os android
cd /path/to/HOA/app/src/main/cpp
MUSL=~/arkui-x-hoa/third_party/musl bash build_musl_bridge.sh
cd /path/to/HOA
./scripts/sync_arkui_x.sh
./gradlew assembleDebug
```

### 修改了 ArkUI-X C++ 代码

```bash
cd ~/arkui-x-hoa
./build.sh --product-name arkui-x --target-os android
cd /path/to/HOA
./scripts/sync_arkui_x.sh --so-only
./gradlew assembleDebug
```

### 修改了 musl bridge 源码

```bash
cd /path/to/HOA/app/src/main/cpp
MUSL=~/arkui-x-hoa/third_party/musl bash build_musl_bridge.sh
cd /path/to/HOA
./gradlew assembleDebug
```

### 修改了 Java/Kotlin 代码

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 附录: HOA manifest

维护在 `harmony-on-android/manifest` 仓库的 `hoa-weekly` 分支：

```xml
<!-- hoa-weekly.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <remote fetch="https://gitcode.com/harmony-on-android" name="hoa" />
  <include name="default.xml" />
  <project path="arkcompiler/ets_runtime" name="arkcompiler_ets_runtime"
           remote="hoa" revision="hoa-weekly" />
  <project path="build" name="build"
           remote="hoa" revision="hoa-weekly" />
  <project path="build_plugins" name="build_plugins"
           remote="hoa" revision="hoa-weekly" />
  <project path="foundation/appframework" name="app_framework"
           remote="hoa" revision="hoa-weekly" />
  <project path="foundation/arkui/ace_engine/adapter/android"
           name="arkui_for_android" remote="hoa" revision="hoa-weekly" />
  <project path="foundation/arkui/napi" name="arkui_napi"
           remote="hoa" revision="hoa-weekly" />
  <project path="plugins" name="plugins"
           remote="hoa" revision="hoa-weekly" />
  <project path="third_party/musl" name="third_party_musl"
           remote="hoa" revision="hoa-weekly" />
</manifest>
```
