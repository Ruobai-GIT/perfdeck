# PerfDeck

自研的 Android 性能 / 调度调校工具，功能面对标 **Scene**（通道管理、命令台、脚本化调校），
但**全部代码与资源均为本工程自写**：

> **本工程没有复制任何第三方应用的代码或资源。**
> `:core` 的命令策略、特权通道抽象、脚本引擎，以及 `:app` 的 Shizuku 桥与 Compose 界面
> 都是按公开 API（Android SDK / Shizuku）自行实现的。第三方依赖（见下表）只作为二进制
> 依赖通过 Gradle 引入，没有把它们的源码或资源拷进本仓库。

## 模块结构

```
perfdeck/
├── core/                    纯 JVM 模块（无 Android 依赖，可在任何 JDK 17 环境测试）
│   └── src/main/kotlin/io/perfdeck/core/
│       ├── shell/           ShellRequest / ShellResult / OutputObserver / ShellExecutor / CommandPolicy
│       ├── privilege/       PrivilegeChannel / ChannelStatus / ChannelRegistry / ShizukuShellBridge 契约
│       ├── script/          TuningScript / ScriptParser / ParameterBinder / ScriptRunner
│       └── util/            PosixShell 引用、BoundedText 有界缓冲
├── app/                     Android 应用模块（Shizuku 桥 + Compose UI）
│   ├── src/main/aidl/io/perfdeck/app/          IUserService.aidl / IOutputCallback.aidl
│   ├── src/main/kotlin/io/perfdeck/app/
│   │   ├── MainActivity.kt                     唯一 Activity，装配依赖 + 授权闸门
│   │   ├── PerfDeckApplication.kt              进程内依赖容器（PerfDeckServices）
│   │   ├── shizuku/PerfShellService.kt         跑在 Shizuku 进程里的用户服务
│   │   ├── shizuku/ShizukuShellBridgeImpl.kt   ShizukuShellBridge 的真实实现
│   │   ├── shizuku/ShizukuPermissionGate.kt    授权检查 / 申请 / 结果回调
│   │   └── ui/                                 PerfDeckApp（三个 Tab）、BuiltInScripts、theme/
│   └── src/main/AndroidManifest.xml
├── .github/workflows/android.yml              CI：构建 debug APK 并上传
├── settings.gradle.kts                        按条件 include(":app")
└── gradle/libs.versions.toml                  版本目录（AGP 8.9.1 / Kotlin 2.1.20 / Compose BOM 2025.04.00）
```

`settings.gradle.kts` **按条件**纳入 `:app`：只有检测到 `ANDROID_HOME` / `ANDROID_SDK_ROOT`
或存在 `local.properties` 时才 include。因此在没有 Android SDK 的机器上（例如手机里的
aarch64 容器），`gradle :core:test` 依然可以完整跑通，`:app` 会被自动跳过。

## `:core` 已实现的能力（已通过单元测试）

| 能力 | 说明 |
| --- | --- |
| 命令执行 | `ShellExecutor`：`sh -c` 执行、有界输出（默认每流 256KB，如实统计总字节）、超时先 SIGTERM 后 SIGKILL、协程取消即杀子进程 |
| 安全策略 | `CommandPolicy`：写块设备 / 格式化 / 刷机 / 递归删系统目录 → **永久拒绝**；remount rw / reboot recovery / wipe → **需要确认** |
| 特权通道 | `UnprivilegedChannel`（应用自身身份）、`RootChannel`（`su -c`，整条命令作为单个 argv）、`ShizukuChannel`（本地先判策略，再交给 Shizuku） |
| 通道选择 | `ChannelRegistry`：`probeAll()` / `select(required, overrideId)`；需要 root 而只有 Shizuku 时返回「需要 root」而**不降级** |
| 脚本引擎 | `ScriptParser`（解析 + 结构校验）、`ParameterBinder`（类型校验 + POSIX 引用，唯一的命令拼接点）、`ScriptRunner`（逐步执行，遇确认/失败即停） |
| sysfs 发现 | `SysfsFs`（带根目录覆盖，可对真实 /sys 或测试 fixture 读）、`CpuTopologyDiscovery`（分频率域、容量、在线核）、`GpuDiscovery`（kgsl/devfreq）、`ThermalDiscovery` |
| 实时监控 | `CpuLoadTracker`（解析 /proc/stat 算 CPU 占用率）、`Monitor`（每轮汇总：各域频率/调速器、热区温度、GPU 频率、CPU 占用，滚动快照） |
| 调校项 | `TuneActions`（校验 governor/频率档后生成写入命令）、`TuneScriptFactory`（把动作包成可执行脚本，走既有的脚本引擎 + 通道） |
| 内存 / IO | `MemInfoParser`（/proc/meminfo → 已用/swap/占比）、`BlockStorageDiscovery`+`ZramDiscovery`、`VmTuneActions`（swappiness/dirty/vfs_cache/min_free 白名单+范围）、`IoTuneActions`（调度器/read_ahead，zram 不给写）、`GpuTuneActions`（max_gpuclk/max_freq，档位校验）、通用 `ScriptFactory` |
| 场景预设 | `Presets`（均衡/省电/性能/游戏）+ `PresetApplier`：结合真实发现的频率域与可用档位，fail-closed 校验后展开成一条 root 脚本；调速器按首选列表回退、锁频 clamp 到「不超出上限的最高档」 |

`:app` 与首次产 APK 的交接说明见 [HANDOFF.md](HANDOFF.md)。

`:core` 的 sysfs 发现/监控/调校逻辑不但有 fixture 单测，还在这台容器里对**真实 `/sys`** 做了集成验证
（双频率域、真实频率档位、真实热区都能解析出来）。

## 在 Android Studio 里构建

1. 用 Android Studio 打开工程根目录（会自动生成 `local.properties`，从而纳入 `:app`）。
2. 需要 **JDK 17**、**Android SDK Platform 36**、**Build-Tools 35.0.0+**。
3. 直接运行 `:app` 配置，或命令行：

```bash
# 仓库自带 wrapper，版本锁定 8.13
./gradlew :app:assembleDebug
# （本机没有 Android SDK 时，settings 会自动跳过 :app，只能跑 :core）
./gradlew :core:test
```

> **关于 Gradle Wrapper**：仓库**已提交** `gradlew` / `gradlew.bat` / `gradle/wrapper/**`，
> 版本锁定在 `gradle/wrapper/gradle-wrapper.properties`（8.13）。CI 与本地都用 `./gradlew`，
> 无需手装 Gradle。若更新 Gradle 版本：`./gradlew wrapper --gradle-version <新版本>` 并提交改动。

## 只跑 `:core` 测试（arm64 容器 / 无 Android SDK 的机器）

```bash
# APP 会被自动跳过；只要 JDK 17
gradle :core:test
# 或强制（如果误设了 ANDROID_HOME 又不想构建 :app）
gradle :core:test -PperfdeckIncludeApp=false
```

`:core` 是纯 JVM 模块，没有 Android 依赖，测试用 JUnit 5；容器里没有 `su` 也不会失败
（`RootChannel.probe()` 会如实返回「不可用」）。

### 必须用 UTF-8 locale（踩过的坑）

测试方法名是中文反引号名，会变成**非 ASCII 的 class 文件名**；如果环境 locale 是 `POSIX`/ASCII
（`LANG` 为空），JVM 的 `sun.jnu.encoding` 就是 `ANSI_X3.4-1968`，编译会直接失败：

```
java.nio.file.InvalidPathException: Malformed input or input contains unmappable characters:
  .../ChannelsTest$?????????????$1.class
```

解决：跑构建时带上 UTF-8 locale（Linux 容器/服务器同理）：

```bash
LC_ALL=C.UTF-8 LANG=C.UTF-8 gradle :core:test
```

macOS / Windows / GitHub Actions 默认就是 UTF-8，无需额外设置。
验证方式：`java -XshowSettings:properties -version 2>&1 | grep sun.jnu.encoding` 应显示 `UTF-8`。

本机已验证的组合：**JDK 17（OpenJDK 17.0.20）+ Gradle 8.13 + Kotlin 2.1.20**，
`gradle :core:test` → 62 个测试全部通过。

## Shizuku 使用前提

1. 设备安装 **Shizuku**（`moe.shizuku.privileged.api`）并按官方指引启动它的服务
   （Android 11+ 走「无线调试」配对，或设备已 root 时直接用 root 启动）。
2. 打开 PerfDeck → **通道** 页 → 点击 **申请 Shizuku 授权**，在系统弹窗里允许。
3. 授权后 Shizuku 通道会以 **shell（uid=2000）** 身份执行命令；若 Shizuku 以 root 启动，
   则身份是 uid=0。App 侧「命令台」里的「需要 root」开关要求的是 **root 通道**（`su`），
   与 Shizuku 不是一回事。

权限相关的行为细节：
- Shizuku 未安装 / 未运行 / 未授权 / 版本过低，四种情况在「通道」页会给出不同文案与
  修复建议（`ShizukuShellBridgeImpl.availability()`）。
- 授权弹窗 60 秒无响应会按「未授权」收尾，不会一直挂着。

## CI

`.github/workflows/android.yml`：

- `ubuntu-latest` + `actions/checkout@v4` + `actions/setup-java@v4`（temurin 17）+
  `android-actions/setup-android@v3`，然后用 `gradle/actions/setup-gradle@v4` 装 Gradle 8.13；
- 从 `app/build.gradle.kts` 里推导 `compileSdk` 并安装对应的 platform / build-tools；
- `gradle :app:assembleDebug`（`--stacktrace --no-daemon`）；
- 上传 `app/build/outputs/apk/debug/*.apk` 为 artifact `perfdeck-debug-apk`。

## 依赖说明

`app/build.gradle.kts` 只用了 `gradle/libs.versions.toml` 里**已有**的别名，
**没有改 catalog、也没有新增版本号**。`material3` 的版本由 Compose BOM `2025.04.00` 统一管理。

两处刻意的取舍，避免为了"写法好看"而新增依赖：

1. **内置脚本用内联 JSON，而不是 `TuningScript(...)` 构造。**
   `:core` 把 `kotlinx-serialization-json` 声明为 `implementation`，不会传递给 `:app`；
   `:app` 也没有应用 `kotlin-jvm`/serialization 编译器插件，因此不能引用 `TuningScript`
   或 `Json`。`BuiltInScripts` 因此改为内联 JSON + `ScriptParser.parseAll(...)`，
   语义完全等价（同一套解析/校验路径），且零新增依赖。
2. **`lifecycle-viewmodel-compose` 没有加入。** 本工程只用「Activity 持有依赖容器
   （`PerfDeckServices`）+ 传入 Compose」的方式做状态管理，不需要 `viewModel()`。
   若后续要引入 ViewModel，应在 catalog 里新增 `androidx-lifecycle-viewmodel-compose`
   （version.ref = `lifecycle`）后再使用。

## 已知取舍与需要人工验证的点

见根目录的交付报告；简要列出最容易出问题的地方：

1. **AIDL 包名必须与目录一致**：`app/src/main/aidl/io/perfdeck/app/IUserService.aidl` 的
   `package io.perfdeck.app;` 与文件路径必须逐级对应，否则 AGP 的 aidl 任务直接失败。
2. **provider authority 必须等于 `applicationId + ".shizuku"`**：`ShizukuProvider` 内部用
   `getPackageName() + ".shizuku"` 拼 URI。改了 `applicationId` 时
   `android:authorities="${applicationId}.shizuku"` 会自动跟随，但**不要**手写成固定字符串。
3. **用户服务版本号**：`ShizukuShellBridgeImpl.USER_SERVICE_VERSION` 变化才会让 Shizuku
   重建用户服务进程。改了 `PerfShellService` 的行为后应把它 +1，否则可能连上旧进程。
4. **Compose 状态线程**：`OutputObserver` 的回调来自后台线程，必须像
   `PerfDeckApp.mainThreadObserver()` 那样切回主线程再写 `mutableStateOf`。
5. **`:app` 无法在本仓库的 arm64 容器里编译验证**（没有 Android SDK / 连不上 Google Maven），
   所有 Kotlin / Compose / AIDL 代码都只做了静态自查（包名与目录一致、core 符号存在性、
   括号/字符串闭合、内置脚本 JSON 可解析）。
6. **仓库里没有 Gradle Wrapper**，CI 用 `gradle/actions/setup-gradle@v4` 安装 Gradle 8.13
   后直接调用 `gradle`；本地 Android Studio 直接用自带 Gradle 即可。
