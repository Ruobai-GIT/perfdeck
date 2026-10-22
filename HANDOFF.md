# PerfDeck — 上手/构建交接

一个自研的 Android 性能与调度调校工具（对标 Scene 的功能面，**不复制任何第三方代码与资源**）。
纯 JVM 核心（`:core`）已在本机容器内完成 111 个自动化测试并针对真实 `/sys`、`/proc/meminfo` 做集成验证；
Android 壳（`:app`）已写完但**尚未在任何有 Android SDK 的环境真正编译过**（本机是 aarch64 容器）。

## 测试现状（可在本机复现）

```bash
# 本机（无需 Android SDK）：只跑 JVM 核心
./gradlew :core:test            # 111 项，全绿
# 注意：测试名是中文反引号名，会产生非 ASCII class 文件名，请用 UTF-8 locale：
LC_ALL=C.UTF-8 LANG=C.UTF-8 ./gradlew :core:test
```

## 构建 APK（二选一）

### A. GitHub Actions（推荐，无需本地环境）
1. 把这个工程推到任意 GitHub 仓库（默认分支 `main`/`master`）。
2. workflow `.github/workflows/android.yml` 会自动跑：
   `validate wrapper → :core:test → :app:assembleDebug → 上传 APK artifact`。
3. 到仓库 Actions 页拿 `perfdeck-debug-apk` artifact。
   - compileSdk/targetSdk=36，CI 自动装 `platforms;android-36` 与 `build-tools;36.0.0`（没有则回退 35）。

### B. Android Studio（本地）
1. 用 Android Studio 打开工程根目录（会自动生成 `local.properties` → 纳入 `:app`）。
2. 需要 JDK 17、Android SDK Platform 36、Build-Tools 35.0.0+（Android Studio 一般自带 SDK；SDK 管理器里装 Platform 36）。
3. Run 配置选 `:app`，或命令行 `./gradlew :app:assembleDebug`。

## 已知“第一次编译 :app 最可能报错/需要确认的点”（写得保守但无本地验证）

1. **Compose BOM 2025.04.00 / Material3 1.3.x**：项目只用了稳定 API；若个别符号（如 `colorScheme.outlineVariant`）在你本地不存在，换成 `surfaceVariant` 即可（代码里已是有意避开易变 API）。
2. **AIDL**：`IUserService.aidl` / `IOutputCallback.aidl` 用到了 `@nullable`，需要在 AGP 8.9 / build-tools ≥ 30 才支持——AGP 8.9 满足；若你在旧工具链上构建可在生成后去掉 `@nullable`。
3. **Shizuku provider authority**：manifest 里 `${applicationId}.shizuku`，与 `getPackageName()+".shizuku"` 对齐；不要手写死包名。
4. **编译排查**：`:core` 已绿，若 `:app` 报错，多为 Compose API 或 AIDL 细节，按报错改即可，核心逻辑（在 `:core`）不受影响。

## 功能地图

- **通道**：应用身份 / root(`su -c`) / Shizuku；需要 root 而无 root 时不降级、明确提示。
- **命令台**：特权命令执行，实时输出，安全策略拒写块设备/格式化/刷机/`rm -rf /data`，高危操作需确认。
- **脚本**：JSON 脚本引擎（参数类型/范围校验 + POSIX 引用），失败即停 / optional / 确认后重跑。
- **监控**：CPU 占用（/proc/stat）、各频率域频率/调速器、热区温度、GPU 频率、内存（/proc/meminfo）、块设备 IO/调度器、zram。
- **调校**：CPU 调速器/频率（scaling max/min/锁频）、内存 vm 项、IO 调度器/read_ahead、GPU max 频率。
- **场景预设**：均衡 / 省电 / 性能 / 游戏 一键应用（解码到具体设备可用档位并校验）。

## 里程碑与剩余
- 已完成并验证：特权框架、命令策略、脚本引擎、sysfs 发现、实时监控、内存/IO/GPU 调校、场景预设（全部在 :core 有自动化测试，部分对真实 sysfs 验证）。
- 待第 1 次真正产出可安装 APK：走上面 A 或 B。之后是真机调试（Shizuku 授权、root 探测、UI 手感）。

---

## CI 踩坑记录（GitHub Actions，2026-10-22）

以下问题按出现顺序排列；每条都给了**根因**与**最终修法**，避免重复踩。

### 1. `Error resolving plugin [id: 'org.jetbrains.kotlin.android'] ... already on the classpath with an unknown version`
- **根因**：`:core` 用 `kotlin.jvm`、`:app` 用 `kotlin.android`，两者是同一个 KGP 的不同 facet。
  根 `build.gradle.kts` 只 `apply false` 了 `kotlin.jvm` 与 `serialization`，Gradle 就只登记了
  jvm facet 的版本；`:app` 再请求 android facet 时无法校验 → 报错。
- **修法**：根 `build.gradle.kts` 里把 Kotlin 全家插件（jvm / android / compose / serialization）
  **全部** `apply false` 声明，与子模块的 `alias(libs.plugins.xxx)` 对应。
- **注意**：本地只 include `:core`，所以这个错**在本地永远不会出现**；只有 CI（有
  `ANDROID_HOME` → include `:app`）才会触发。改根插件声明后必须靠 CI 才能验证。

### 2. `Failed to apply plugin 'org.jetbrains.kotlin.android' > Could not create an instance of type KotlinAndroidTarget > com/android/build/gradle/api/BaseVariant`
- **根因**：KGP 生成 `KotlinAndroidTarget` 的装饰类时要加载 AGP 的
  `com.android.build.gradle.api.BaseVariant`。AGP 只 `apply false` 在子模块、与 KGP
  不在同一条 plugin classpath 上 → `ClassNotFoundException`。
- **修法**：把 `alias(libs.plugins.android.application) apply false` 也加进**根**
  `build.gradle.kts`，让 AGP 与 KGP 进同一个 classpath。
- **验证方式**：`ANDROID_HOME=<sdk> gradle :app:help` 能在本地复现/验证此错（无需完整编译）。

### 3. `SDK location not found` / `Could not start '.../build-tools/34.0.0/aidl'`
- **根因**：AGP 8.7.3 默认 build-tools 是 34.0.0，而 CI/本地只装了 35.0.0。
- **修法**：`app/build.gradle.kts` 显式 `buildToolsVersion = "35.0.0"`；workflow 里
  **从该文件读** `buildToolsVersion` 来安装，且不做静默 fallback（装错版本会让
  `compileDebugAidl` 以 "No such file or directory" 失败，极难排查）。

### 4. `WARNING: This Android Gradle plugin (8.7.3) was tested up to compileSdk = 35`
- **根因**：`compileSdk = 36` 超出 AGP 8.7.3 的测试范围。
- **修法**：`compileSdk` / `targetSdk` 降到 **35**（AGP 8.7.3 完整支持的版本，零警告）。
  本项目没有用到 API 36 独有 API，无功能损失。

### 5. AGP 与 KGP 的版本搭配（重要经验）
- AGP 8.9.1 + KGP 2.1.20 会在 `:app` 配置阶段崩（见问题 2）。
- **已验证可用组合**：AGP **8.7.3** + Kotlin **2.1.20** + compileSdk/targetSdk **35**
  + Gradle 8.13 + Compose BOM 2025.04.00。`:core:test` 111 项全绿，`:app` 全部
  Kotlin 源码可完整编译。

### 6. `:app` 首次真实编译暴露的源码级 bug（全部已在本地修掉）
这些问题在 `:core` 编译或 `:app:help` 阶段都发现不了，只有真正编译 `:app` 才暴露：

| 文件 | 问题 | 修法 |
| --- | --- | --- |
| `ui/BuiltInScripts.kt` | **KDoc 注释里写了 `${c##*/cpu}`，其中的 `*/` 提前终止了块注释**，导致后面全部内容被当成顶层代码，报一片 `syntax error: Expecting a top level declaration` | KDoc 里绝不出现 `*/`；改写成文字描述 |
| `ui/BuiltInScripts.kt` | JSON 位于 **raw string** `"""` 内，而 raw string 中 `\$` **不是转义**——`$` 照样触发字符串模板，于是 `\${c##*/cpu}` 被解析成对 `c` 的引用，报 `unresolved reference 'c'` | raw string 里表示字面 `$` 必须写 `${'$'}`；已把 36 处 `\$` 全部替换 |
| `MainActivity.kt` | `import io.perfdeck.app.ui.theme.PerfDeckTheme` 路径错误（`PerfDeckTheme` 在 `ui` 包，不在 `ui.theme`） | 改成 `io.perfdeck.app.ui.PerfDeckTheme` |
| `MainActivity.kt` | `override fun onRequestPermissionsResult(permissions: Array<out String>)` 与平台签名 `Array<String>` 不匹配（Array 是不变的） | 改成 `Array<String>` |
| `shizuku/PerfShellService.kt` | `override fun destroy()` 覆盖不存在——AIDL 接口没声明 `destroy()` | AIDL 加 `void destroy() = 16777114;`（事务码与 Shizuku 官方 demo 一致） |
| `ui/tuning/TuningScreen.kt` | `FlowRow` 在 Foundation 1.7 仍是 `ExperimentalLayoutApi` | 加 `@OptIn(ExperimentalLayoutApi::class)` |
| `ui/monitor/MonitorScreen.kt` | 在 `Canvas { }` 的 lambda（`DrawScope`，非 Composable）里读 `MaterialTheme.colorScheme.outlineVariant` | 把颜色提到 Composable 函数体再传进去 |
| `ui/monitor/MonitorViewModel.kt` | `mutableIntStateOf(DEFAULT_INTERVAL_MS)` 传了 `Long`；`delay(intervalMs)` 传了 `Int` | `DEFAULT_INTERVAL_MS.toInt()` + `delay(intervalMs.toLong())` |
| `AndroidManifest.xml` | Shizuku 的 `<provider>` 多了 `android:permission="android.permission.INTERNET"`（本应用不联网） | 删掉该属性 |

### 7. 本地无法完整出包的原因（环境限制，非代码问题）
容器是 **arm64**，而 Android SDK 的 `aidl` / `aapt2` 只发布 **x86_64** 二进制，且无 qemu：
```
$ /root/android-sdk/build-tools/35.0.0/aidl
bash: cannot execute: required file not found
```
因此本地最多验证到「`:app` 全部 Kotlin 编译通过 + manifest 处理 + 资源合并」，
最后的 `compileDebugAidl` / `processDebugResources` 必须在 x86_64 的 CI 上完成。

**本地验证 `:app` Kotlin 能否编译的办法**（不依赖 AGP）：
1. `gradle :app:dependencies` 导出依赖，解出所有 AAR 的 `classes.jar`；
2. 手写一份与 AIDL 等价的 `IUserService.java` / `IOutputCallback.java`（见
   `moe.shizuku` 的 AIDL 生成物格式），用 `javac` 编译；
3. 用 `kotlin-compiler-embeddable-2.1.20.jar` + `kotlin-compose-compiler-plugin-embeddable-2.1.20.jar`
   编译 `app/src/main/kotlin`，classpath 含 `platforms/android-35/android.jar`。
   （必须给 kotlinc 加 Compose 编译器插件，否则后端会报
   `couldn't find inline method ... rememberCoroutineScope$default`。）

### 9. 启动闪退：ICU 正则比 JVM 正则严格（真机专属，本地测试测不出）

**现象**：装机后点图标立刻退出，logcat 里连一行 `Log` 都没有。真正的记录在
dropbox：`/data/system/dropbox/data_app_crash@*.txt`：

```
java.lang.ExceptionInInitializerError
    at io.perfdeck.app.PerfDeckServices.<init>(PerfDeckApplication.kt:79)
Caused by: java.util.regex.PatternSyntaxException: Syntax error in regexp pattern near index 28
    \{\{\s*([A-Za-z0-9_.-]+)\s*}}
                            ^
        at com.android.icu.util.regex.PatternNative.compileImpl(Native Method)
    at io.perfdeck.core.script.ParameterBinder.<clinit>(ParameterBinder.kt:112)
```

**根因**：Android 的 `java.util.regex` 底层是 **ICU**（`com.android.icu.util.regex`），
和桌面 JVM 的 `java.util.regex` 不是同一套实现，且严格得多。**字面花括号必须转义**：
JVM 把裸的 `}` 当字面量，**ICU 直接抛 PatternSyntaxException**——上面 index 28
指的就是倒数第一个 `}`。

它出现在 `companion object` 的 `val` 上，于是 `<clinit>` 失败 →
`ExceptionInInitializerError` → `PerfDeckServices` 构造失败 → `onCreate` 抛出 → 闪退。

> 注意走过的弯路：第一次只把字符类里的 `-` 挪到最前面（`[A-Za-z0-9_.-]` →
> `[-A-Za-z0-9_.]`），**并没有修好**——真凶始终是 `}}`，崩溃位置分毫未变。
> 两个写法都得改，`-` 那条属于"顺手加固"，不是根因。

**为什么本地 `:core:test` 全绿也发现不了**：单元测试跑在 JVM 上，走 JVM 的正则实现，
天然覆盖不到 ICU 差异。这个坑只有真机能暴露。

**修法**：字面花括号成对转义 —— `\s*}}` → `\s*\}\}`（`ParameterBinder.PLACEHOLDER`）。
已验证进包：dex 里 `\s*}}` 出现 0 次、`\s*\}\}` 出现 1 次。

**防回归**：`core/src/test/.../RegexIcuCompatibilityTest.kt` 静态扫描
`:core`/`:app` 里所有 `Regex(...)` 字面量，命中两类 ICU 敏感写法即失败：
未转义的花括号、字符类里非首位的 `-`。
它必须用 `settings.gradle.kts` 向上定位仓库根——**Gradle 跑单测的工作目录是模块
目录（`core/`），写死 `core/src/main/kotlin` 会一条都扫不到，让检查静默空转**；
另有一条 `扫描器必须能找到源码里的正则` 断言扫到的条数下限，防再次空转。

**排查手法备忘**：Android 上闪退但 logcat 空白时，别只看 logcat，直接读
`/data/system/dropbox/data_app_crash@*.txt`（需 root）。`pm install` / `am` 在
设备 shell 策略里被拦，只能由用户手动装；但 `pm path` / `pm list packages` /
`logcat` / `cat` / `cp` 可用。判断能否覆盖装：用 `keytool -printcert` 比对已装 APK
与待装 APK 的证书指纹是否一致。
本地没有 Android 的 ICU 引擎，ICU4J 各版本都已把 `RegexPattern` 摘掉，
无法在容器里当 oracle 用——只能靠人工审 + 静态检查 + 真机验证。
