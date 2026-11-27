# Qwen 听写 Android 交接

## 当前分工

更新于 2026-11-28。当前发布版本为 `v0.2.2`。

| 负责人 | 构建范围 | 交接内容 |
| --- | --- | --- |
| Codex | 后端构建，包括 `llama.cpp` 运行时、CPU / Hexagon HTP0、Gradle 风味、Android 前台服务、模型加载和 API 网关。 | 提供 APK 或构建命令，记录运行时版本、目标芯片、预期后端、构建结果及已确认的真机结果。服务接口有变化时，注明前端需要适配的参数、状态与错误。 |
| Antigravity | Android 前端构建，包括 Jetpack Compose 页面、导航、交互与服务状态展示。 | 记录受影响的页面、依赖的服务接口及构建结果。需要增加服务状态或操作时，在交接记录中说明触发场景和预期行为。 |

`MainActivity.kt` 和 `DictationModel.kt` 连接 Compose 界面与 `DictationService.java`。涉及服务指令、状态字段或硬件能力展示的改动，由两边在同一条记录中更新接口行为与验证结果。每次交接记下日期、提交、涉及的风味、验证结果和未决问题。真机验收详情见 [VERIFICATION.md](VERIFICATION.md)。

## 芯片构建状态

| 目标芯片 | 后端 | 构建结果 |
| --- | --- | --- |
| 天玑 9400+ | CPU | 已完成 |
| 骁龙 8 Elite | CPU | 已完成 |
| 骁龙 8 Elite | Hexagon NPU | 未成功，失败阶段和原因待确认 |
| 骁龙 8 Elite Gen 5 | Hexagon NPU | 已完成 |

以上为目前确认的构建结果。APK 安装、运行时初始化和推理结果在有对应样本时记录。仓库的 `cpu` 风味使用通用 ARM64 运行时，`snapdragon` 风味使用 Snapdragon 运行时并支持 CPU 与经设备验证的 HTP0。构建命令见 [README.md](README.md#构建与测试)。

## 后端待交接

- **骁龙 8 Elite 的 Hexagon NPU 构建**由 Codex 跟进。已有 CPU 构建可作对照。确认失败阶段后，补充对应的构建命令、运行时版本和首个失败点。

## 本地推理性能预设

更新于 2026-11-28。Codex 已接入后端，Antigravity 已在推理设置页接入「性能与内存」预设选择并在服务页与设置总览联动展示。

### 性能预设前端适配记录（Antigravity · 2026-11-28）

- **受影响页面**：
  - `SettingsScreens.kt`（`cpu` 风味「设置 → 推理参数」与 `snapdragon` 风味「设置 → 推理与硬件」）：新增「性能与内存」卡片，接入 `保护前台 (foreground)` / `均衡 (balanced)` / `全速 (full_speed)` 三档即时切换，展示当前预设、进程运行/待重载状态、实际启动配置（线程、逻辑批、微批与 `RAM 缓存 0 MiB`）、启动优先级（`nice` 应用结果）与批量段间间隔，外部 ASR 提示本地预设不可用；并在设置总览副标题同步显示当前预设。
  - `ServiceScreen.kt`：端侧推理引擎卡片在运行中展示当前生效预设与待重载切换提示。
- **依赖服务接口**：`DictationService.performanceStatus(context)` 与 `DictationService.savePerformancePreset(context, id)`，已封装于 `DictationModel.kt`。
- **涉及风味**：`snapdragon`、`cpu`（版本号升级至 `0.2.2` / `versionCode 5`）。
- **验证结果**：`gradle --offline :app:verifyPinnedRuntime :app:verifyPinnedCpuRuntime :app:assembleSnapdragonDebug :app:assembleCpuDebug :app:assembleSnapdragonDebugAndroidTest :app:testSnapdragonDebugUnitTest :app:testCpuDebugUnitTest :app:lintSnapdragonDebug :app:lintCpuDebug` 全部通过（含新增 `ContractTest#performanceLimitPresetsAndStatusBoundaries` 单测）。

| 预设 | CPU 线程 `-t` / `--threads-batch` | 逻辑批 `-b` | 微批 `-ub` | 启动优先级 | 批量识别间隔 |
| --- | --- | --- | --- | --- | --- |
| 保护前台 `foreground` | 高级设置值，上限 4 | 高级设置值，上限 512 | 上限 256 | `nice +1` | 300 ms |
| 均衡 `balanced`（默认） | 高级设置值 | 高级设置值 | 上限 256 | `nice +1` | 0 ms |
| 全速 `full_speed` | 高级设置值 | 高级设置值 | 上限 512 | 继承系统 | 0 ms |

- 本地 `llama-server` 各档都显式使用 `--cache-ram 0`。微批同时受逻辑批大小约束。上下文、最大输出、模型、卸载层数使用现有配置。外部 ASR 地址走原服务。
- 旧 `limitInferencePerformance=true` 的安装迁移为保护前台。未设置或已关闭低占用开关的安装使用均衡。前端保存 `savePerformancePreset(context, id)`。旧 `savePerformanceLimit(context, boolean)` 继续映射到保护前台或均衡。
- `performanceStatus(context)` 返回 `available`、`selectedPreset`、`activePreset`、`reloadRequired`、`nativeThreads`、`nativeBatch`、`nativeMicroBatch`、`nativeNiceAdjustment` 和 `bulkGapMillis`。当前没有本地进程时 `activePreset` 为空。数字字段为启动配置，`nativeNiceAdjustment` 为本轮已应用的调整，系统拒绝时为零。
- 保存预设后，文件、Kikoeta、OpenAI 和 DashScope 的段间等待从下一次批量推理生效。实时听写不插入自己的段间等待。等待可取消，且不计入单段 RTF。线程、微批和优先级在用户完成任务并释放、重载模型后生效。RTF 故障恢复沿用任务原来加载时的预设。
- 启动日志记录预设、实际选择的后端、传入的线程、批大小、上下文、输出上限、RAM 缓存与优先级。运行时上报的 CPU、HTP 和 HTP-HOST 模型、KV、计算缓冲大小会以 MiB 写入日志。HTP 自检存储按有效推理参数和微批区分，新预设首次加载可参考历史自检，最终验证由实际卸载状态决定。

**独立运行时证据**

- SM8850（`18c3df38`）使用 Qwen3-ASR 1.7B Q8_0、HTP0、上下文 2048。默认缓存运行到音频 29:20 时记录 220 个缓存状态，共 3912.435 MiB，触发整机可用内存 1792 MiB 保护线。关闭缓存跑完 30:01.75，峰值 PSS 约 575 MiB，前 220 段逐字一致。
- 同一段前 8 分钟保持 4 线程、`nice +1`、`-b 512`，微批由 512 降至 256，HTP 计算缓冲由 48 降至 24 MiB，HTP-HOST 计算缓冲由 10.02 降至 5.01 MiB，CPU 音频编码计算缓冲仍为 553.89 MiB。去掉首段的 P95 RTF 从 0.3853 降至 0.3481，60 段输出逐字一致。
- 上述实验直接启动 `llama-server`。App 内预设切换、多应用并行、复杂音频和非 Hexagon CPU 真机表现继续独立记录。

## RTF 失控预警与断点续传

Codex 已实现设置存储、RTF 监控、模型重载、四个转写入口的分段恢复，以及同步网关的恢复预算。Antigravity 已完成推理设置页开关与预警值接入，并在日志、文件转写进度与服务状态页中呈现恢复状态。三款目标芯片的真机恢复验收待完成。

### 前端适配记录（Antigravity · 2026-11-27）

- **受影响页面**：
  - `SettingsScreens.kt`（`cpu` 风味「设置 → 推理参数」与 `snapdragon` 风味「设置 → 推理与硬件」）：新增即时生效「RTF 失控预警」卡片，展示当前实际后端、对应模型、阈值模式与生效数值（自动模式注明固定余量），提供总开关、自动/手动模式切换、有限正数校验与即时保存（无需重载模型），外部 ASR（`external`）提示本地重载不可用；并在设置总览页副标题展示当前预警开关状态。
  - `FileScreen.kt`：文件转写恢复期间展示「恢复中」状态标签、预警横幅与保留断点说明；失败中断时明示已保留的中断前确认字幕数量与断点时间戳。
  - `LogsScreen.kt`：新增恢复中实时横幅、`RTF 预警` 组件一键筛选、`RTF 预警` 日志行高亮标签，以及读取应用私有 `rtf-incidents`（最近 24 条）的持久化恢复记录弹窗（支持复制与导出）。
  - `ServiceScreen.kt`：端侧推理引擎状态卡片在重载/重试期间显示「恢复中」并禁用释放按钮。
- **依赖与新增服务接口**：
  - 封装 `DictationService.rtfStatus(context)` 与 `DictationService.saveRtfSettings(context, modelId, backend, enabled, manualLimit)` 于 `DictationModel.kt`。
  - 在 `DictationService.java` 新增只读状态方法 `static boolean recovering()`（透传 `activeEngine.recovering()`），并在 `startFileTranscription` 的 `Inference.Control` 中实现 `recovering(boolean active)` 回调以在 `FileTask.message()` 呈现恢复/继续状态。
  - 按用户反馈将 `RtfWatchdog.java` 基线样本不足时的冷启动默认阈值从 CPU `8.0` / HTP0 `4.0` 下调为 **CPU `1.2`、HTP0 `0.8`**（保留 `3 × 基线 RTF 中位数` 动态计算与 `1 秒` 固定余量）。
- **涉及风味**：`snapdragon`、`cpu`。
- **验证结果**：
  - 构建与单测：`gradle --offline :app:assembleSnapdragonDebug :app:assembleCpuDebug :app:testSnapdragonDebugUnitTest :app:testCpuDebugUnitTest :app:lintSnapdragonDebug :app:lintCpuDebug` 全部通过（含新增 `ContractTest#rtfWatchdogThresholdValidationAndBoundaries` 单测）。
  - 真机前端交互（SM8850 / `18c3df38`，Android 17 / API 37，`snapdragon` Debug）：已验证设置页副标题、RTF 失控预警自动/手动模式切换、非法值（`0`）校验拦截、正小数（`2.5`）即时保存与冷重启持久化、总开关关闭/开启即时生效、`CPU`/`HTP0` 后端切换阈值联动（冷启动默认 `1.20` vs `0.80` + `1 秒固定余量`）、以及日志/服务/文件页展示，全程无崩溃。
- **待验收**：三款目标芯片（天玑 9400+ CPU、骁龙 8 Elite CPU、骁龙 8 Elite Gen 5 HTP0）的异常注入与真机恢复闭环验收。

### 设置

前端可在同包的 `DictationModel.kt` 中封装 `DictationService.rtfStatus(context)`。返回值包含 `modelId`、实际 `backend`、启用状态、手动值、有效 RTF 阈值和自动模式的固定余量。保存时把读到的 `modelId` 与 `backend` 传给 `saveRtfSettings(context, modelId, backend, enabled, manualLimit)`。传入 `null` 切回自动。外部 ASR 地址的后端标识为 `external`，本地 RTF 重载不可用。

最近 24 条告警及恢复结果保存在应用私有的 `rtf-incidents` 记录中。应用运行期间可通过现有日志页查看 `RTF 预警` 组件。

两种构建都在现有推理设置页提供「RTF 失控预警」。通用 CPU 版的入口为「设置 → 推理参数」，骁龙版为「设置 → 推理与硬件」。

| 设置 | 行为 |
| --- | --- |
| RTF 失控预警 | 本机总开关，默认开启。关闭后仍记录片段 RTF，RTF 越界计数、告警与由 RTF 触发的重载停用。引擎明确报告截断或进程故障时，继续执行对应的处理。 |
| 预警值 | 可选「自动」或「手动」，默认自动。手动值接受有限的正数和小数，实测 RTF 严格大于设定值才算越界。例如设为 `2.0`，8 秒音频的推理用时超过 16 秒才计一次。手动模式直接使用输入值。 |

设置保存在本机，按当前模型和实际推理后端记住阈值模式与手动值。切到尚未配置的模型或后端时，使用自动模式。例如同一台骁龙手机的 CPU 和 HTP0 可以各自保存不同阈值。界面显示当前实际后端、阈值模式和生效数值。自动模式有固定的 1 秒余量，界面注明该余量。

保存后无需重载模型，新设置从下一次推理尝试起生效，计数从零开始。已启动的恢复继续到完成或失败。若设置改变时还有被扣留的片段，使用新设置重试该片段后再决定是否提交。

### 采样与触发

- 在 `InferenceScheduler` 串行执行的本地 ASR 调用内采样。`RTF = 模型请求耗时 / PCM 片段时长`，用单调时钟计时。排队、媒体解码、翻译和模型加载单独计时。已跳过的静音段以及用户取消的调用不参与计数。
- 自动阈值按机型与固件、模型、实际后端、运行时版本和推理参数区分。以同配置最近 9 个健康片段（至少 5 个、每段至少 4 秒）的 RTF 中位数为基线。暂定片段用时上限为 `3 × 基线 RTF × 音频时长 + 1 秒`。基线不足时暂用 CPU RTF 1.2、HTP0 RTF 0.8，再加 1 秒固定开销。只用通过完整性检查的正常样本更新基线。上线阈值以三款目标芯片的实测数据校准。
- 从第 1 次越界起扣留该次输出，原段复试。每次收到推理响应时记录一个 RTF 样本，正常且完整的输出清零计数。第 3 次连续越界时记录首次异常和触发的绝对时间，丢弃本段未提交输出，开始重载。复试前确认上一个原生请求已结束。推理请求超时或原生进程退出时，由运行时故障路径独立恢复。
- `RuntimeLog` 用 `WARN` 记录告警，并在应用私有存储留取有界记录。记录时间、模型、实际后端、任务类型、片段起点、三次 RTF、当时阈值和重载结果。恢复成功与失败分别写入日志。日志仅包含诊断元数据。

### 重载和提交边界

保留 `Inference`、调度队列及任务对象，只重载其中的本地 `llama-server`。重载期间新推理请求在同一队列等待，网关继续处理健康查询、状态和取消。旧进程退出后用原模型、`mmproj` 和实际后端启动新进程，沿用健康检查与卸载验证。服务的进程监控需识别主动重载状态。引擎就绪后把保存的同一段 PCM 和语言参数重新提交，沿用原采样点与任务 ID。

片段通过检查后才更新输出和进度，断点为最后一个已提交片段的结束采样点。若引擎显式报告输出长度或上下文触顶，当前片段拆短后重试，即使 RTF 正常也扣留被截断的结果。每个失败片段最多自动重载一次。重载或复试仍失败时保留已确认进度并返回明确错误。若外部推理引擎无法由应用重载，错误需带出该能力限制。

| 入口 | 恢复行为 |
| --- | --- |
| 本地音视频文件 | 保持解码器、当前 PCM 和已生成字幕。重试成功后再追加字幕并推进 `processedMs`。失败界面可保留已确认的字幕。 |
| Kikoeta | 沿用原 `jobId`、事件游标、音轨和 `nextSample`。恢复成功后继续生成同一份 LRC。只有音轨全部完成后才发送 `file_done` 并纳入缓存。暂停或取消仍在提交前生效。 |
| OpenAI Audio / DashScope | 保持原同步请求、上传临时文件和已确认文本。所有片段完成后才发送一次成功响应。恢复失败时沿用各自的错误体，HTTP 503 表示模型不可用。 |

`Wire` 的同步请求有 3 分钟正常处理预算。等待复试和重载的时间计入 10 分钟的服务端总上限。到达服务端时限且客户端仍在线时返回协议对应的 HTTP 503。客户端主动断开时取消请求。客户端自身的读取超时仍适用。

### 验收

- 验证关闭预警后连续越界仍会记录 RTF，且不触发 RTF 重载。验证手动值的等值边界、开关和阈值改动后清零、重启保留设置，以及 CPU 和 HTP0 各自取值。
- 注入可识别的序列（正常、超标、超标、正常）验证计数清零。对同一音频片段注入三次高 RTF 和一段可辨认的截断文本，核对告警时间、重载次数和原段复试。
- 四条入口分别核对恢复前、恢复段和恢复后的字幕内容与时间戳。可疑文本不能进入导出、Kikoeta 缓存或 API 成功响应，已完成片段不得重复。覆盖恢复失败、用户取消、Kikoeta 暂停、API 客户端断开和明确的输出长度触顶。
- 真机覆盖天玑 9400+ CPU、骁龙 8 Elite CPU、骁龙 8 Elite Gen 5 HTP0，记录正常 RTF 分布、触发时间到恢复完成的用时，以及各入口最终输出。

RTF 预警可阻止已判定为异常的片段被提交。若文本被静默截断且 RTF 与终止状态均正常，当前响应缺少可靠的完整性信号。

---

下文为 2026-11-18 从 Android 0.1.1 开始的重构方案。

2026-11-18 · 起点为 Android 0.1.1 · 本文供实现、设计和真机验收使用。

## 1. 产品目标

Qwen 听写是一款端侧听写、翻译、字幕应用，也能为其他应用提供语音识别服务。首次配置后，冷启动直接进入「实时翻译」。用户能够在应用内下载模型、检测设备、开始采集和查看日志。离线翻译是首版默认路径，联网翻译是手动开启的选项。Kikoeta 保留现有接入，同时新增 OpenAI Audio 与阿里云百炼 DashScope 原生协议。

已确认两款 ASR 模型为 **Qwen3-ASR-0.6B Q8_0** 和 **Qwen3-ASR-1.7B Q8_0**。轻量版使用 Qwen 官方的 0.6B 名称，某些 Hugging Face 页面或转换统计会显示约 0.9B。UI 只显示一款轻量版。用户所说的 Dashboard 是百炼 **DashScope** API。

## 2. 现有工程与真机基线

| 位置 | 已有功能与接手注意事项 |
| --- | --- |
| `MainActivity.java` | Java 原生单页，设置、导入、字幕混排。前端重构的主要替换入口。 |
| `DictationService.java` | Android 前台服务管理推理、麦克风、播放音频捕获和悬浮字幕。0.1.1 先绑定 API，再异步预热模型。 |
| `Inference.java` | 启动打包的 llama-server，固定 HTP0、CPU 音频编码和 2048 上下文。也有手填外部引擎地址和 OpenAI 兼容文本翻译。现有工作路径可用于回归。 |
| `LiveCapture.java`、`Pcm.java` | 16 kHz PCM、3 秒实时分段和 8 秒批量分段、静音过滤、转写与翻译分队列、SRT/LRC。保留可复用采集与导出逻辑。 |
| `Jobs.java`、`Wire.java` | Kikoeta 的 Basic Auth、任务状态、事件游标、缓存、结果下载。薄 HTTP 实现未覆盖通用 multipart、流式协议或 LAN 入口。 |
| `Stage-Hexagon.ps1`、`app/build.gradle` | 从外部路径暂存 llama.cpp 原生制品。当前资源位于忽略的 `app/runtime/`。 |

- 已验证设备是 SM8850、Hexagon v81、Android API 37，llama.cpp 为 build 12470、commit `dde9de82c`。普通 APK 进程可驱动 HTP。1.7B Q8_0 主模型的 29/29 层卸载到 HTP0，音频编码器走 CPU，播放内录、SRT 和 Kikoeta LRC API 在真机通过。GPU、HTP 音频编码、0.6B 和离线翻译仍需基准测试。
- 保留包名 `dev.workspace.qwen.asr`、签名升级路径、Kikoeta 地址 `127.0.0.1:2370`、Basic 账号及随机密码、已导入 GGUF、已完成缓存和私有 LRC 路径。现有 `files/model.gguf` / `files/mmproj.gguf` 通过元数据或摘要识别后原地登记，迁移失败时仍可使用文件导入。签名不能复用时需先确定数据导出和迁移方式。
- HyperOS 曾在前台服务运行时冻结其 UID。应用级电池优化豁免后，Kikoeta 在前台连续 60 秒访问缓存成功。首次指南中仅在诊断到相关问题时提供系统应用设置入口，并保留独立的 API 监听状态与模型状态。

## 3. 新 Android 界面和交互

建议用 Kotlin、Jetpack Compose 与 Material 3 实现新页面，通过 `ViewModel` / `StateFlow` 订阅服务状态。现有 Android Service、AudioRecord、MediaProjection、导出和协议代码可逐步迁移，服务与关键状态独立于 Activity 生命周期。Compose 依赖需在实现阶段固定到当前 AGP/SDK 可用版本。

底部导航按「实时翻译 / 服务 / 日志 / 设置」布局。紧凑屏和横屏均可操作。首页采用高对比度的字幕画布，主色建议深靛青配单一青绿色强调，提供跟随系统的亮暗色方案。译文主字号 22–26sp，原文 14–16sp，使用支持中日文的系统字体。状态仅用可读短标签，确认触控区至少 48dp、200% 字号可用、TalkBack 可读，并在新字幕到达时保持输入按钮位置稳定。

| 页面 | 屏幕内容与行为 |
| --- | --- |
| 实时翻译（默认首页） | 顶部显示语种、模型与实际后端状态。主区域显示待机、加载、监听、原文、译文及掉段。底部固定音源、开始/结束。支持麦克风、获授权的播放音频、原文/译文/双语显示、跟随最新句、选中文本、悬浮字幕和 SRT 导出。用户向上翻阅时停止自动滚动。译文按片段 ID 更新对应原文。首次打开模型未就绪时只引导配置。 |
| 服务 | 独立开关与状态，显示绑定 IP、端口、三种协议的实例 URL、认证信息、模型预热状态、请求数、队列和错误。凭据默认遮挡，显式复制。提供连通测试和可复制的最小示例。 |
| 日志 | 显示 llama.cpp 和应用运行时的结构化实时日志，区分级别、组件和会话。支持搜索、过滤、暂停跟随、查看最新、复制、清空和脱敏导出。 |
| 设置 | 分组入口为模型、推理、翻译、音频、服务和诊断。高级推理参数放在子页，变更前标出即时生效或需重新加载。模型页显示已安装、下载速度、剩余空间、校验状态和卸载入口。 |

首轮设计验收需交付首页的未配置、下载中、识别中、已出双语、设备不支持播放捕获、离线包缺失六种状态，以及服务页和推理设置页的真机截图。首页展示真实功能文案及错误。

## 4. 首次安装

1. 检查 ABI、系统版本、剩余存储及本次安装可用的运行时组件。首页允许稍后配置。首次打开无需录音或悬浮权限。
2. 短时探测 CPU、GPU、NPU，显示“可用 / 探测失败 / 当前构建未提供 / 不支持”。检测超时后仍可选择 CPU。显示推荐的运行组合与原因。
3. 选择轻量或高质量模型。默认推荐轻量档，展示主模型与匹配 mmproj 的总大小。支持 Wi-Fi 下载、显式允许移动网络、进度、取消、断点续传、磁盘不足提示，以及使用 SAF 导入 GGUF。配套文件通过校验后一起生效。
4. 选择源语种或自动检测、目标语种。建议默认目标为简体中文。下载并验证离线翻译包，缺包时可先进入首页听写。允许在模型页后续补齐。
5. 短样本本机自检，展示实际音频编码与语言模型后端。随后进入实时翻译首页。第一次点麦克风、播放捕获、悬浮字幕时再发起对应的系统权限流程。服务监听默认仅本机。

引导进度可恢复。升级安装识别已配置状态，直接进入首页。首次模型下载需要联网，资产齐备后的听写和离线翻译须可在飞行模式完成。

## 5. 运行时自动配置与推理设置

### 设备和模型

- 交付内置运行时和固定版本构建脚本。CI 从经审核的 llama.cpp commit 与固定 Android 工具链构建 arm64 制品，记录 ABI、编译选项、库文件及 SHA-256。随 APK 打包或随签名版本交付，应用安装后自行加载。Windows 测试路径与 ADB 暂存只用于开发构建，用户只通过模型页和设置页操作。
- CPU 为基础路径。GPU 通过实际打包的 OpenCL 或 Vulkan 后端枚举及试跑，NPU 首批为 Qualcomm Hexagon HTP。芯片名和 Android 能力信息仅作提示，后端必须在普通 App UID 下成功初始化。测试 CPU、GPU、NPU 的可用性需分别记录。运行请求记录实际卸载层数和主要计算设备，也报告混合执行。
- 设备探针包含运行时枚举和一条短音频的试跑，缓存键包含固件指纹、ABI、运行时版本、设备与模型。升级、崩溃或驱动异常后允许重测。自动档仅选择已经验证的组合。人工档失败时展示确切组件与原因，提供一键切回自动或 CPU。禁止静默把失败的分配报告为 NPU 或 GPU。
- 模型清单固定上游 revision、两个 GGUF 的精确 URL、长度、SHA-256、格式、许可和匹配关系。使用临时文件、断点续传、校验和原子替换。禁止把模型放进 Git。现有 1.7B 真机资产可用于对照。

| 模型卡 | 主文件 | 配套文件 | 发布前验收 |
| --- | --- | --- | --- |
| 轻量 · Qwen3-ASR-0.6B Q8_0 | `Qwen3-ASR-0.6B-Q8_0.gguf`，约 805 MB | `mmproj-Qwen3-ASR-0.6B-Q8_0.gguf`，约 214 MB | 核实完整下载清单，CPU 与支持的加速组合均通过短样本。 |
| 高质量 · Qwen3-ASR-1.7B Q8_0 | `Qwen3-ASR-1.7B-Q8_0.gguf`，约 2.17 GB | `mmproj-Qwen3-ASR-1.7B-Q8_0.gguf`，约 356 MB | 在 SM8850 复现 HTP 层卸载、重启和旧资源迁移。 |

大小为十进制展示值，两款模型加上配套文件的下载量分别约为 1.02 GB 和 2.53 GB。UI 的精确大小来自发行清单。 [轻量版文件](https://huggingface.co/ggml-org/Qwen3-ASR-0.6B-GGUF/tree/main) · [高质量版文件](https://huggingface.co/ggml-org/Qwen3-ASR-1.7B-GGUF/tree/main)

### 用户可选组件

| 设置 | 默认和选项 | 实施约束 |
| --- | --- | --- |
| 音频文件解码 | 自动、Android 系统硬件/软件解码、PCM/WAV 直读 | 按 MIME、设备与 codec 枚举后显示，统一混音、重采样到模型所需 PCM。麦克风和系统播放采集本身走 PCM。 |
| ASR 音频编码器 / mmproj | 自动、CPU、已验证 GPU、已验证 NPU | CPU 路径已在真机验证。`--no-mmproj-offload` 能选择 CPU。加速路径要验证具体算子和 projector 调度，独立指定设备若现有二进制不支持，需以绑定设备的 JNI/libmtmd 或受控小补丁实现。 |
| ASR 文本生成 / 语言模型解码器 | 自动、CPU、已验证 GPU、已验证 NPU | 通过运行时枚举设备、配置层卸载并核对实际 log/buffer。用户可以组合音频编码和文本生成设备。 |
| 优先级与预设 | 自动平衡、低延迟、高质量、省电 | 使用经基准测试的预设组合。手动详情可覆盖字段，设有恢复默认。 |
| 高级推理 | CPU 线程、批尺寸、上下文、最大输出、卸载层数、单实例并发和空闲释放 | 为每个运行时版本记录参数支持及边界。换模型或后端应用于新会话，处理中的任务可完成或经用户确认后取消。 |
| 分段与字幕 | 源语言提示、VAD、最短/最长分段、静音阈值、端点等待、实时队列上限 | 先保持已验证的约 3 秒窗口，再用 VAD 对照漏字、重字、首句时延及积压。时间戳基于样本计数，导出使用最终片段。 |

首版必须验证 CPU→CPU 与 CPU 音频编码→HTP 文本生成。CPU→GPU、GPU→GPU、GPU→HTP、HTP→HTP 等组合按实测开放。CPU 音频编码＋HTP 文本生成是 SM8850 上的已知可用配置。保留一个常驻推理实例，内部监听独立 loopback 和随机令牌。所有外部接口通过应用网关，前台服务状态与 HTTP 预热状态独立。 [llama.cpp Snapdragon 后端](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/snapdragon/README.md)

### 翻译与运行日志

- 定义 `Translator` 接口，接收转写文本、源语言、目标语言和片段 ID。默认离线，完成相邻语段的有限上下文处理，译文迟到也能回填对应时间段。开始时显示离线包状态。缺包或翻译失败时保留原文和 SRT 能力。联网翻译只有在用户配置并选中后才发送转写文本。
- 第一轮工程验证以 ML Kit 端侧语言包为候选，评估日语→简中、英语→简中及 SM8850 目标固件的下载可用性。日中质量需用对照样本验证，ML Kit 的非英语语言对经英语中转。若国行设备无法稳定下载，发布前换成可从应用镜像交付、许可合规的独立离线翻译包。离线方案选型以断网真机和日中质量验收决定。 [ML Kit 端侧翻译概述](https://developers.google.com/ml-kit/language/translation/) · [Android 模型管理](https://developers.google.com/ml-kit/language/translation/android)
- Runtime 日志事件包含时间、级别、组件、run ID、模型和可脱敏消息。默认捕捉启动、设备分配、加载失败、prompt/decode 时间和重启，采用有限环形缓冲。日志页可看实际设备、卸载层、RTF、排队和掉段计数。凭据、音频数据、转写内容和含签名的 URL 在进入缓冲前清除。分享日志由用户触发。

## 6. API 网关与网络策略

统一内核使用 `AudioInput → InferenceScheduler → Transcript(segments, language) → Translator?`。协议适配只负责校验、鉴权、生命周期和序列化。每个适配器独立开关，限连接数、音频大小、任务时长和队列深度，断开即停止无用同步任务。直播采集优先，批量任务在片段边界让出并保留进度。文件与流各用有界缓冲。

```mermaid
flowchart LR
    Live["麦克风 / 播放采集"] --> Audio["音频归一化与分段"]
    API["Kikoeta / OpenAI / DashScope"] --> Gateway["Android API 网关"]
    Gateway --> Audio
    Audio --> Scheduler["推理调度"]
    Scheduler --> Engine["llama.cpp ASR"]
    Engine --> Text["原文和时间戳"]
    Text --> View["首页、导出或 API 响应"]
    Text --> MT["端侧翻译 / 可选联网翻译"]
    MT --> View
    Engine --> Logs["脱敏实时日志"]
```

| 协议 | 首版接口与兼容边界 |
| --- | --- |
| Kikoeta | 保持 `/api/v1/health`、`/api/v1/jobs`（创建/读取/事件/取消/暂停/恢复）、`/api/v1/cache` 与结果下载。默认原有本机端口和 Basic Auth。保留结果索引、事件游标、缓存格式及目标 LRC。继续允许模型加载期间查询缓存。 |
| OpenAI Audio | `GET /v1/models` 与 `POST /v1/audio/transcriptions`，Bearer，multipart 的 `file`、`model`、可选 `language`。先支持 `json` 和 `text`，对能力外的参数和格式返回明确的 OpenAI 风格错误。两款模型有稳定 ID，收到未安装的模型请求返回提示。实现 `verbose_json`、SRT/VTT、翻译或 SSE 前分别定义时间戳、目标语言和流式事件契约。回归用原生 OpenAI SDK 访问可配置 base URL。 |
| 百炼 DashScope 同步 ASR | `POST /api/v1/services/aigc/multimodal-generation/generation`，Bearer 本地 Key。接受文档的 `input.messages` 音频内容和受支持的 `parameters.asr_options`，返回 `output.choices[].message.content`、`request_id`、用量字段和原生错误格式。先支持 Base64/受策略约束的 HTTP(S) 音频 URL。用真实 DashScope SDK 替换 endpoint 测试。云模型别名需显式映射到本地模型，默认配置展示实际 ASR 模型。 |
| 百炼实时与异步扩展 | 实时任务以选定的百炼 WebSocket 事件族为验收契约，优先对照 Paraformer 任务式 `run-task → task-started → PCM → result-generated → finish-task → task-finished`，另测 `task-failed` 和连接复用。若目标客户端使用 Qwen3-ASR-Flash-Realtime 会话协议，独立实现并测试。长音频异步可复用任务队列，采用百炼 `POST /api/v1/services/audio/asr/transcription` 与 `GET /api/v1/tasks/{task_id}` 的提交和轮询语义。首版按目标客户端确定必需的扩展，界面按已通过的协议能力展示。 |

在线文档和测试 fixture 固定到实现时采用的 API 契约。将 HTTP 路径、鉴权、缺字段、重试、队列满、取消和错误体纳入契约测试。Kikoeta 自带 URL 和请求头，跨源跳转只转发允许的头。通用 URL 取音默认限制经过验证的协议、来源和目标网络，逐次检查重定向和最终连接地址。设备内网来源由用户单独授权，限制本机服务、链路本地地址、额外凭据和绝对文件路径访问。

[OpenAI Audio API](https://platform.openai.com/docs/api-reference/audio/createTranscription) · [DashScope Qwen-ASR REST](https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference) · [DashScope Paraformer WebSocket](https://help.aliyun.com/zh/model-studio/websocket-for-paraformer-real-time-service) · [Kikoeta Transl](https://github.com/chenflxs/kikoeta-transl)

| 端口模式 | 绑定和访问判定 |
| --- | --- |
| 仅本机（默认） | API 绑定 `127.0.0.1` 和可用的 `::1`。保留对相应适配器的认证。 |
| IP 白名单 | 按用户选择的绑定接口启动 listener，支持 IPv4/IPv6 主机与 CIDR。根据 TCP 对端地址判断，统一 IPv4 映射地址。空列表仅允许本机，默认忽略代理伪造的来源头。IP 检查适用于 HTTP 和 WebSocket。 |
| 全开 | 对选定接口允许任意来源 IP；每个协议继续要求 API Key 或 Basic。展示实际绑定地址和网络范围，启用需用户主动选择。支持 TLS 或受控的外部 TLS 终止，管理界面未授权时保持本机。应用不配置公网端口映射。 |

变更端口和模式要验证数字范围、占用、双栈绑定与证书配置。绑定失败保持原监听，界面显示原因。转为更严格模式时，立即拒绝或终止已经失去权限的连接。密钥放在本机安全存储，接口健康页在鉴权后展示详细信息。限流和请求体限制独立于 IP 策略。

## 7. 实施顺序与验收门槛

| 阶段 | 交付 | 完成条件 |
| --- | --- | --- |
| A · 固化基线 | 原型源码、可复现构建、设备探针、两份模型清单 | 固定工具链和上游 commit，干净环境构建，核对已验证的 SM8850/CPU 路径；现有 Kikoeta 通过回归。 |
| B · 新首页与首次引导 | Compose 导航、模型管理、硬件推荐、实时翻译和日志页 | 全新安装可走完引导至首页。权限拒绝、网络中断、下载续传、校验失败和低存储都可恢复。旧 APK 升级能读模型和缓存。 |
| C · 端侧翻译与推理设置 | 离线语言包、音频与文本设备组合、自检、日志导出 | 飞行模式中对短日语和英语生成简中双语 SRT。量化测试两个模型，记录首段延迟、译文补齐延迟、RTF、峰值内存、掉段、异常回退及 10 分钟稳定性。阈值基于设备基准设定。 |
| D · 服务网关 | 本机/白名单/全开、OpenAI 和 DashScope、Kikoeta 兼容 | Android 和局域网客户端各跑真实 SDK。IPv4/IPv6、合法和非法 IP、令牌失效、并发、上传限制和端口切换有实测。再次验证 HyperOS 切回 Kikoeta 后缓存请求持续成功。 |

需要保留的自动化结果包括模型清单验证、设置迁移、API golden 响应、时间戳/任务状态测试和一份真机验证记录。建议真机至少覆盖当前 SM8850 和一台无 Hexagon 的 arm64 Android，GPU 档有实际设备证据才开放。

待在 A 阶段定案的是 DashScope 的目标 SDK/客户端样本（决定实时及异步扩展进入首版的顺序）、最终离线翻译包和可公开发布的许可证及签名策略。模型文件可再分发、界面语言包和各地区证书信任条件分别查证。以上重构功能在 0.1.1 基线中尚未实现。

## 资料索引

- Qwen 官方模型名称、语种和 ASR 能力。 [QwenLM/Qwen3-ASR](https://github.com/QwenLM/Qwen3-ASR)
- 两个 GGUF 转换仓库和配套音频 projector。 [0.6B](https://huggingface.co/ggml-org/Qwen3-ASR-0.6B-GGUF/tree/main) · [1.7B](https://huggingface.co/ggml-org/Qwen3-ASR-1.7B-GGUF/tree/main)
- Android 播放捕获和前台服务。 [Audio playback capture](https://developer.android.com/media/platform/av-capture) · [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
