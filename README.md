# HOA — Harmony on Android

在 Android 设备上运行 OpenHarmony / HarmonyOS HAP 应用。

**HOA 是一个正在开发中的实验性项目**，目标是让 HarmonyOS 的 HAP 应用包无需修改即可在 Android 设备上运行。目前已完成核心运行时搭建，ArkTS 页面渲染和原生 .so 加载均已验证通过，但大量系统 API 尚未实现，兼容性有限。

## 支持到什么程度

| 能力 | 状态 | 说明 |
|------|------|------|
| ArkTS 页面渲染 | 支持 | SurfaceView + Skia，SDK 5.0/6.0 ABC 格式兼容 |
| HAP 安装与启动 | 支持 | ZIP 解析、流式解压、多进程槽位管理（5 槽） |
| resources.index 解析 | 支持 | V1（OHOS 5.0）和 V2（SDK 6.0）双版本 |
| 原生 .so 加载 | 支持 | 无 RUNPATH 的闭源 .so 拓扑依赖加载 + musl ABI bridge（libb.so） |
| C++ STL（libc++_shared） | 支持 | std::string/vector/map/thread/exception/RTTI 等全部通过 |
| HDC 协议 | 支持 | TCP 8710，支持 install/uninstall/shell/file send，DevEco Studio 可识别为设备 |
| 权限映射 | 部分 | 普通权限自动映射（INTERNET 等），危险权限需手动处理 |
| 生命周期 | 部分 | onCreate/onResume/onPause/onDestroy 已对接，部分中间状态未实现 |
| HDS 组件 | 部分 | 70+ 导出 mock，功能正确但视觉与原版差异较大 |

| 完整 Bundle Manager | 不支持 | 未实现，`bundleInfo_ is nullptr` 日志警告 |
| 后台能力 / Service | 不支持 | 未实现 |
| 分布式 / 多设备 | 不支持 | 未实现 |

## 已知限制

HOA 仍在早期开发阶段，以下为已知限制：

- **HAP 兼容性**：目前只有部分 HAP 能正常运行。依赖未实现系统 API 的 HAP 会白屏或崩溃。
- **性能**：ArkUI-X 渲染管线未经优化，复杂页面可能卡顿。
- **内存**：每个 HAP 启动一个独立进程，多 HAP 同时运行内存压力较大。
- **稳定性**：长时间运行可能出现资源泄漏，建议测试后重启 app。
- **安全性**：HDC daemon 无认证机制（默认 8710 端口），仅适合开发调试，不要在生产环境使用。

## 原理

HOA 基于 ArkUI-X 的 Android 构建体系，通过多个仓库的定向适配使运行时能够加载并执行 OHOS 原生格式的 HAP：

- **ArkTS 渲染**：ETS VM 执行 modules.abc 字节码，ACE 引擎将 UI 树渲染到 Android SurfaceView
- **原生 .so 支持**：通过 `libb.so`（musl ABI bridge）在 Android bionic 上运行 OHOS 闭源 .so；ELF patch 将 DT_NEEDED `libc.so` 替换为 `libb.so`；`elf_load_with_deps` 对无 RUNPATH 的 .so 做拓扑依赖加载
- **HDC 协议**：实现 OHOS HDC daemon，支持 DevEco Studio 设备识别与一键部署

```
┌─────────────────────────────────┐
│  HAP (entry.hap)                │
│  ├── module.json                │
│  ├── ets/modules.abc            │  ← OHOS 原生字节码
│  ├── libs/arm64-v8a/*.so        │  ← OHOS 闭源 .so（musl ABI）
│  ├── resources.index            │
│  └── resfile/                   │
└──────────┬──────────────────────┘
           │ HapInstaller 解压 + ELF patch (libc.so → libb.so)
           ▼
┌─────────────────────────────────┐
│  HOA Application                │
│  ├── StageApplication           │  ← ArkUI-X Android 适配器
│  ├── libarkui_android.so        │  ← 内嵌 ETS VM + ACE 渲染引擎
│  ├── libb.so (musl bridge)      │  ← musl ABI 桥接（pthread/stdio/dirent/signal）
│  └── OHOS HAP Mode Patches      │  ← 7 仓库定向适配
└──────────┬──────────────────────┘
           │
           ▼
┌─────────────────────────────────┐
│  Android SurfaceView            │
│  └── Hello World (ArkUI)        │
└─────────────────────────────────┘
```

关键机制：Java 层通过 `setOhosHapMode(true)` 设置环境变量，经 JNI 传入 ETS VM，在模块路由时激活 OHOS 兼容路径（自动适配 SDK 5.0/6.0 ABC record 名格式差异），使 ArkUI-X 能正确加载 OHOS 编译的 ABC 文件。

## 界面

宿主界面与 KiTerminal-UX 共用同一套设计系统：Miuix（MIUI 风格 Compose 组件）+ 玻璃材质顶栏 / 底栏，主题跟随系统深浅色与 Material You。主页拆成两个标签页：

| 标签页 | 内容 |
|--------|------|
| **应用** | 已安装 HAP 的网格、搜索、排序、长按菜单（启动 / 卸载 / 添加到桌面）、安装预览 |
| **设置** | 机型模拟、默认排序、应用权限、HDC 服务、外观、检查更新、反馈 |

原先挂在 `MainActivity` 选项菜单里的设置项已全部迁到设置页，菜单本身不再保留。

- API 33+ 使用浮动玻璃底栏（AGSL 运行时着色器）；API 30~32 自动回退到 Miuix 经典 `NavigationBar`。
- 玻璃材质相关代码以 vendor 方式放在 `app/src/main/java/top/yukonga/` 下，与上游组件库解耦，便于逐文件同步。

## 机型模拟

HOA 允许把"上报给 HAP 的设备信息"改成任意机型，设置页 → **模拟机型** 提供一批华为 / 鸿蒙预设，也可以 **自定义机型** 手动填写字段，或选 **跟随宿主设备** 用真机信息。

实现在 `app/src/main/java/app/hackeris/hoa/device/`：

- `DeviceProfile` — 机型档案（brand / manufacturer / model / product / device / board / hardware / fingerprint / osVersion）
- `DeviceProfileStore` — 持久化到 `hoa_device_profile` SharedPreferences
- `BuildSpoofer` — 在 `HoaApplication.onCreate()` 最早期用 `sun.misc.Unsafe`（失败退回反射）覆盖 `android.os.Build` 的静态字段

生效范围：

- `ohos.ace.plugin.device_infoplugin.DeviceInfoPlugin` 直接读 `Build.MODEL` 等静态字段，因此 `@ohos.deviceInfo` 拿到的是模拟后的值；
- HDC `param get` 的 `const.product.model / brand / devicetype / software.version` 等分支读同一份档案；
- 不在范围内的：`Build.FINGERPRINT` 以外的厂商私有字段、以及 HAP 里直接调 Android API 的场景。

## 状态栏避让

HAP 页面里 `window.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM)` 之前恒返回 0，原因是宿主 Activity 自己给 `android.R.id.content` 加了状态栏高度的 padding：

ArkUI-X 的 `WindowViewCommon.processAvoidAreas()` 用 `intersection(WindowView 窗口矩形, (0,0,screenW,insets.top))` 算顶边避让区，而窗口矩形取自 `getLocationOnScreen()`。content 被 padding 顶下来之后，窗口起点正好等于 `insets.top`，两者求交恒为空。

现在的做法（`HoaAbilityActivity.configureEdgeToEdgeForHap()`）：

1. `setDecorFitsSystemWindows(window, false)`，insets 一路下发到 WindowView 根视图；
2. content 不加 padding、不消费 insets（监听器原样返回），窗口矩形保持 `(0, 0, w, h)`；
3. 系统栏透明，状态栏图标明暗按 HAP 窗口背景亮度决定。

这样顶边避让区就是真实状态栏高度，**宿主不再替 HAP 让位**，由 HAP 自己（ArkUI 默认的 safe area，或显式 `expandSafeArea`）决定内容铺到哪。

## 构建

> **首次构建前请务必阅读 [`docs/BUILD.md`](docs/BUILD.md)**，其中包含前提条件、工具链安装、源码下载、编译及常见问题的完整说明。

构建概览：

```
build_all.sh (ArkUI-X → libb.so → sync → APK)

或分步：
repo init (HOA manifest) → prebuilts_download.sh → build.sh → build_musl_bridge.sh → sync_arkui_x.sh → gradlew assembleDebug
```

构建耗时数小时不等，视机器性能和网络状况而定。磁盘需求约 100GB（源码 + 产物）。

### CI

仓库自带两条 GitHub Actions 流水线，签名材料与 KiTerminal-UX 完全同款（同一个 `app/ki-terminal-release.jks`、同一个 alias、同一组 Secrets 名）：

| Workflow | 触发 | 产物 |
|----------|------|------|
| `build_apk.yml` | `workflow_dispatch`（可选 debug / release / both）、push / PR 到 `main` | `hoa-debug` / `hoa-release` artifact |
| `release_apk.yml` | 发布 Release / 手动指定 tag | 签好名的 APK 自动挂到 Release |

需要的 Secrets：`JKS_BASE64`（keystore 的 base64）、`KI_TERMINAL_STORE_PASSWORD`、`KI_TERMINAL_KEY_PASSWORD`、`KI_TERMINAL_KEY_ALIAS`（可选，默认 `ki-terminal`）。

ArkUI-X 产物（`jniLibs` / `arkui_android_adapter.jar` / `assets/sys`）由 `.github/actions/prepare-arkui-x` 恢复：先查 Actions 缓存，未命中时从本仓库 Release 的 `arkui-x-prebuilts-arm64.zip` 或仓库变量 `HOA_PREBUILT_URL` 下载并回写缓存。**没有这份产物就打不出 APK**——它来自 ArkUI-X 的 100GB 级源码构建，CI 里不会重头编译。


## 运行

- **生产模式**：MainActivity → Install HAP（选择文件）→ 点击启动
- **开发测试**：`adb shell am start -n app.hackeris.hoa/.DevTestActivity --ez autoLaunch true`

要求：Android 11+，arm64-v8a 设备。

## 社区

- **获取 HAP**：可前往 [sydxky.cn](https://sydxky.cn/) 下载 HAP 应用包。由于 HOA 尚在开发中，大部分 HAP 暂无法正常运行，仅建议用于测试。
- **QQ 群**：欢迎加入交流群讨论。

<img src="media/qq_qrcode.jpg" alt="QQ群二维码" width="280">

## 相关文档

- `docs/BUILD.md` — 完整构建文档
- `docs/ARKUI-X_PATCHES.md` — ArkUI-X 源码修改说明
- `agents/PLAN.md` — 技术方案
- `agents/PROGRESS.md` — 项目进展
