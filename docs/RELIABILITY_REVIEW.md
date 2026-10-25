# HAP 安装与运行链路可靠性修复

审查日期：2026-10-25。

## 扫描范围与结构

仓库只有一个 Android `app` 模块，Gradle 为 Android 构建入口；CMake 构建 NAPI / 系统桥接库，`Makefile.musl_bridge` 单独生成 `libb.so`。ArkUI-X 的适配 JAR、运行时原生库和系统资源由外部源码构建或 CI 预编译包提供，不在 Git 中。

主要链路为 `HoaApplication → StageApplication` 初始化运行时，`HapBundleLoader → HapInstaller` 解析安装包，`ProcessSlotManager → HoaAbilityActivity0..9 → StageActivity` 在独立进程中启动 HAP。系统 API 位于 `ohos/ace/plugin`，通知与媒体桥接位于 `bridge`，HDC 是另一条安装和启动入口。

本次快速检查了模块配置、Manifest、启动与销毁、安装与卸载、资源索引、进程槽位、HDC 路由、JNI ELF 补丁和依赖加载，以及 TODO/FIXME/XXX/BUG/HACK。重点修复可从源码直接确认的崩溃和数据损坏路径；这不代表对全部系统插件或外部 ArkUI-X 运行时完成了兼容性认证。

## 已修复问题

| 问题 | 修复 |
| --- | --- |
| `resources/`、`libs/` 条目允许路径穿越；包名可影响目标目录 | 校验包名、模块名、路径组件和规范化后的目录边界；阻止覆盖模块元数据 |
| 升级先删旧版本，失败会丢失安装和用户数据 | 暂存完整资源后替换，保留 `files`、`preference`、`database`；恢复中断的备份并清理暂存目录 |
| 卸载 `com.example` 也会删除 `com.example.other` | 按完整包名精确匹配模块目录 |
| 安装、启动、卸载与运行时读写竞争 | 共用安装锁，运行中或已预约启动的包拒绝升级/卸载；启动使用非阻塞锁，避免主线程等待大型安装 |
| 多线程/多进程重复分配同一个槽位 | 同目录线程锁与 OS 文件锁保护完整读改写，验证槽位所有者，回收过期预约及死亡进程 |
| Activity 重建和退出前过早释放槽位 | 保留 PID 记录至进程真正退出，由分配器检测死亡；配置变化期间保留所有权 |
| HDC 启动未保存模块目录，无法在 Application 初始化前隔离存储 | HDC、详情页、快捷方式和普通启动统一走公共入口；启动失败回收预约并向 UI 报错 |
| 外部应用可直接启动内部槽位 Activity，绕过入口校验和安装锁 | 槽位 Activity 改为不导出；保留快捷方式中转、开发测试和 HDC 入口。进程死亡后保留最后的目录身份，供 Android 重建任务时恢复存储隔离 |
| 模块配置缺包名时未使用 `pack.info` | 回填包名及版本，并写入安装后的配置，避免安装为 `unknown` |
| V1/V2 资源索引接受负偏移和加法溢出 | 使用减法式范围校验，检查负计数、数据边界；畸形输入返回空结果 |
| 无模块前缀的 `resources/...` 图标路径被错误裁剪 | 保留已有资源根路径 |
| 图标缓存升级后不刷新、旧刷新任务覆盖新结果 | 图标随刷新重新读取，取消已过时的刷新任务；重组复用 `HapItem` 中的位图 |
| ELF 程序头、动态表、字符串偏移未做边界校验 | 共享有界 ELF 文件视图，验证范围、对齐、终止符、字符串表与动态表不重叠；短 RUNPATH 不再覆盖后续字符串 |
| `RTLD_NOLOAD` 无法阻止尚未加载的循环依赖 | 显式祖先链检测、遍历深度上限、堆上工作缓冲区；限制依赖路径长度 |
| 开发 HAP 解压未复用安装保护且流/临时文件可能泄漏 | 统一调用安装器，验证预期身份并关闭输入流、清理临时文件 |
| Manifest 中存在游离的 `maxSdkVersion` 文本 | 修正权限声明，并限制旧存储权限的 SDK 范围 |
| 旧单测访问已删除的内存展开字段 | 改为验证当前配置/磁盘安装语义，并补充实际失败路径回归 |
| 初始化失败后仍调用 StageActivity 原生入口 | 必需组件加载失败立即停止工作进程，在宿主进程显示独立错误页；只有运行时和 HAP 模式都就绪才标记成功 |
| ActivityThread 隐藏反射在不同 Android 版本失效 | 使用公开的生命周期回调和弱引用跟踪 Activity，支持 ContextWrapper；权限请求切换到 UI 线程 |
| 通用广播、定位和 Wi-Fi 接收器在新 SDK 上缺少注册标志 | 显式声明接收器导出策略；去除重复订阅泄漏，注销可重复执行；电池状态按订阅独立计算并校验比例 |
| 横屏请求被所有厂商设备统一阻止 | 仅对已知受影响的真实 Xiaomi/Redmi/POCO 宿主保留规避，其他设备转发给系统；补齐字体、语言等配置变化声明 |
| 无数字 ID 的图标不加载，大尺寸图片占用过多堆内存 | 支持命名资源回退，校验资源路径；按尺寸采样到最多 512 像素，合成后回收中间位图 |
| HDC 压缩内容直接落盘，重传可能导致提前完成 | 按 OpenHarmony 格式解码 LZ4；按偏移写入并合并覆盖范围，支持乱序与重复块，拒绝缺块和越界 |
| HDC 长度、文件路径和连接销毁未严格处理 | 限制 protobuf/包长度，约束开发目录、随机安装临时文件，断线清理部分传输，正确返回卸载失败 |
| HDC 启停竞争、端口更新无效、shell 管道死锁或内存无限增长 | 每代监听器独立持有 socket，配置变更重启；流式合并输出并在连接关闭时结束 shell |
| ELF 修复只改了源码，旧预编译 libb.so 不会自动更新 | 新增 CMake 构建的 libharmoid_elf.so 与独立 JNI 类，确保当前源码随每次 APK 构建生效，内部符号隐藏防止旧实现抢占 |
| 预览取消/解析失败留下缓存文件，后台日志格式器并发访问 | 明确临时文件所有权，在取消与销毁时回收；日志时间格式化与写入共用锁 |
| Manifest 创建不存在的 Startup Provider，可能在 Application 前启动失败 | 移除 Provider 声明，最终合并 Manifest 只保留实际存在的 FileProvider |
| Android 12 关于页误用 API 33 RuntimeShader，低版本玻璃绘制缺保护 | 着色器创建及玻璃绘制显式检查 API 33，旧系统保留渐变回退；更新动画参数时同步持有的新参数 |
| 蓝牙权限在授权检查后撤销仍会抛异常，扫描结果可能没有名称或 ScanRecord | 系统调用边界返回权限错误码 201；广播使用 Intent 中的状态，扫描兼容匿名 BLE 和空记录；补齐广告权限 |
| 定位查询共用一次性 latch，后续/并发请求状态错误，主线程可能自阻塞 | 每次查询独立等待状态，显式使用主 Looper 分发更新；主线程无缓存时立即返回，权限撤销和监听清理有保护 |
| 新系统返回手势跳过 StageActivity 的旧回调，硬件声明限制无相关硬件设备 | API 33+ 注册公开返回回调，转发到 ArkUI 返回处理；宿主所需硬件均声明为可选 |
| 相册选择未关闭 Cursor，空结果或缺失 DATA 列导致越界 | 使用 try-with-resources 并校验行、列及权限；相册也使用公开 Activity 跟踪，避免隐藏反射失效 |

## 验证

完整 Android 构建使用 JDK 21、Android API 37、NDK 28.2。独立核心测试可使用本机已有 Android SDK 平台。常规依赖准备参见 [BUILD.md](BUILD.md)。本机从仓库的 [prebuilts-arkui-x Release](https://github.com/TiG-Kira/Harmoid/releases/tag/prebuilts-arkui-x) 恢复 486 个运行时文件，下载包 SHA-256 校验为 `91ee40a8eb6b8d39120142e2ad799a1bdb234fde4983b2caf6ea96f925a9bf27`；这些二进制产物没有提交进 Git。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --console=plain
.\gradlew.bat -p scripts/hap-core-tests test --console=plain
```

- 完整 Debug APK 构建和 60 项单测通过，其中 21 项通过 Robolectric 运行于 Android 12 / 13 / 14；包括真实 TCP 连接上的 LZ4 HAP 安装、服务快速启停、广播替换与注销、Activity 生命周期、大尺寸图标读取、蓝牙权限撤销、旧系统着色器降级、定位主线程保护和相册 Cursor 边界/回收。
- Debug APK 的签名和 ZIP 对齐校验通过；最终合并 Manifest 已确认没有悬空的 Startup Provider。
- Lint 通过：0 错误、126 条告警、3 条提示；原有 98 项错误已修复，没有全局禁用检查或生成 baseline 隐藏问题。告警仍包含外部依赖、隐藏 API、资源和维护性事项，不能理解为全部问题已清零。
- 38 项核心回归可独立编译运行真实安装器、资源解析器、槽位和 HDC 源码，不需要 ArkUI-X 或 Android 模拟器；包括多线程和多个独立 JVM 的槽位竞争。
- 设备测试 APK 可编译；新增对实际打包 JNI 的畸形 ELF 与真实库补丁测试，但当前没有连接的 arm64 Android 设备，未执行设备测试。
- CI 加入独立核心与 sanitizer 检查，完整 Android 构建同时运行单元测试、Lint 和设备测试 APK 编译。

Linux / WSL 原生验证：

```sh
sh scripts/test_elf_patch.sh
build/native-tests/elf_patch_test app/src/main/jniLibs/arm64-v8a/*.so
```

原生测试以 `-Wall -Wextra -Werror`、ASan / UBSan 编译并执行实际 `elf_patch.c`，覆盖有效/重复补丁、短 RUNPATH、截断/溢出/未终止 ELF 数据、循环依赖和 140 层依赖图，另在 1 MB 栈限制下通过。使用同一解析器额外验证了 162 个真实共享库。Android arm64 单文件编译及 APK 内的三个 ElfTools JNI 导出也已校验。

HDC 压缩和偏移处理参考 [OpenHarmony transfer.cpp](https://github.com/openharmony/developtools_hdc/blob/master/src/common/transfer.cpp) 与 [transfer.h](https://github.com/openharmony/developtools_hdc/blob/master/src/common/transfer.h)。原生测试中的 logger 和 dlopen 使用测试桩，不能替代设备上的 Android 动态链接器验证。

## 剩余验证与约定

- 当前预编译包中的 `libasset_ndk.z.so`、`libdeviceinfo_ndk.z.so` 为 4 KB 对齐。针对这两份库及闭源 HAP 原生库，Manifest 申请系统页大小兼容模式；这不等同于原生 16 KB 认证，仍应重新构建这些外部库并在设备验证。机制见 [Android 官方说明](https://developer.android.com/guide/practices/page-sizes#16-kb-backcompat-mode)。
- 新 ELF 修复由 Gradle/CMake 自动打进 APK，不再要求为了这一修复重编整份 musl bridge；`libb.so` 的 GOT/ABI 桥接仍来自外部预编译运行时。
- 尚未进行真实 HAP 渲染、Android 11–16 真机/OEM ROM、16 KB 页设备、WebView、音视频和长时间运行验证。不能据此宣称兼容所有 HAP 或已完成生产可用性认证。
- 升级保留 `files`、`preference`、`database`，缓存与临时目录按可重建处理；应用运行中须先退出再升级或卸载。模块目录维持现有 `bundle.module` 规则，模块名不含点。
- 单 HAP 的 .app 包支持统一安装入口；多 HAP .app、后台/分布式能力、完整 Bundle Manager 和部分系统 API 仍受项目既有限制。HDC 仍是无认证的开发调试接口，不应作为生产管理接口。
- 相册选择仍沿用运行时的文件路径契约；不暴露 DATA 列的文档/云相册 Provider 会安全返回空结果，尚未实现 URI 内容到运行时文件接口的桥接。
- Lint 在外部 `arkui_android_adapter.jar` 中发现两处 `TrustAllX509TrustManager` 告警；本次未确认其调用场景，也未修改该二进制依赖。正式发布前须审计上游网络适配源码并重建运行时，不能仅以宿主 Lint 无错误作为网络安全认证。
