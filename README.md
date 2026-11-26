# Qwen 听写（Qwen ASR Android）

基于 **Qwen3-ASR**（`llama.cpp` 端侧推理）与 **Kotlin / Jetpack Compose（Material Design 3）** 构建的 Android 端侧离线听写、本地音视频转写、翻译字幕及多协议语音识别网关应用。

- 支持 **Android 13+（arm64-v8a）**
- 内置 `llama.cpp b12470` Snapdragon Android 运行时（支持 CPU 及已验证设备上的 Qualcomm Hexagon **HTP0** NPU 卸载）
- 最新安装包可在 [GitHub Releases](https://github.com/lycs-selling-doors/qwen-asr-android/releases) 下载

---

## 核心功能

| 模块 | 功能亮点 |
| --- | --- |
| **实时听写** | 支持麦克风与系统播放音频内录；实时流式展示原文/译文/双语字幕，支持悬浮字幕、语种快捷抽屉与一键导出 SRT。 |
| **文件转写** | 直接导入本地音频与视频文件（MP4 / MKV / MOV / WebM / MP3 / M4A / WAV / FLAC 等），通过 `FileDescriptor` 零拷贝提取音轨，按 8 秒分段实时流式出字，支持单次任务切换语种与输出模式（仅原文 / 双语 / 仅译文），并支持一键复制及导出 **SRT / LRC / TXT**。 |
| **本地 API 服务** | 独立网关开关与实时请求指标，同时兼容 **Kikoeta**、**OpenAI Audio** 与 **阿里云百炼 DashScope** 三种协议，凭据默认加密存储、一键复制地址/密钥/cURL 示例。 |
| **端侧与联网翻译** | 默认支持 ML Kit 端侧离线翻译包（飞行模式可用），也支持手动配置 OpenAI 兼容联网翻译接口或设为「仅听写」。 |
| **运行日志与诊断** | 深色终端风格实时脱敏日志（搜索、分级筛选、暂停跟随、导出），内置 CPU / GPU / NPU 设备枚举与短音频自检。 |

---

## 快速上手

1. **安装模型**：进入「设置 → 语音模型」（或首页首次配置向导），下载或通过系统文件选择器导入一套 Q8_0 主模型与匹配的 `mmproj` 音频编码器（自动校验 SHA-256，支持旧版资产原地迁移）：
   - 轻量推荐：`qwen3-asr-0.6b-q8_0`（约 1.02 GB）
   - 高质量：`qwen3-asr-1.7b-q8_0`（约 2.53 GB）
2. **硬件自检**：在「设置 → 推理与硬件」或「设备诊断」运行短音频自检。自动档优先启用本机已验证的 HTP0，其余情况使用 CPU。
3. **实时听写**：在「听写」页选择麦克风或播放内录并点击录制按钮；点击右上角语种胶囊可快速切换识别语种与翻译模式。
4. **音视频文件转写**：切到「文件」页导入本地音频或视频，选择识别语言与输出模式（默认「仅原文」），点击开始即可在前台服务下流式转写并导出 SRT / LRC / TXT。
5. **对接外部应用（Kikoeta / SDK）**：在「服务」页开启 API 监听（默认监听本机 `127.0.0.1:2370`），可按需切换协议标签页复制地址与密钥。

---

## API 网关与网络策略

| 协议 | 默认状态 | 鉴权 | 接口契约 |
| --- | --- | --- | --- |
| **Kikoeta** | 开启 | Basic（`admin`） | `/api/v1/health`、任务队列、事件流、缓存查询、暂停/恢复/取消与私有 LRC 下载 |
| **OpenAI Audio** | 手动开启 | Bearer | `GET /v1/models`、`POST /v1/audio/transcriptions`（multipart：`file`、`model`、`language`、`json`/`text`） |
| **百炼 DashScope** | 手动开启 | Bearer | `POST /api/v1/services/aigc/multimodal-generation/generation`（Base64 Data URI 或 HTTP(S) 音频地址，支持云模型别名映射） |

### Kikoeta 输出文本可选
- 在「服务 → Kikoeta」或「设置 → 网关与音频来源」中可**即时切换** Kikoeta 任务的输出模式（无需重载模型）：
  - **仅原文（默认）**：跳过翻译步骤，直接输出 Qwen3-ASR 识别原文 `.lrc`（未开启翻译或无离线语言包也可正常使用）。
  - **双语**：以 `[mm:ss.xx]原文 / 译文` 单行格式输出 `.bi.lrc`。
  - **仅译文**：输出目标语言 `.zh.lrc`。
- 若客户端请求体显式传入 `flags.enable_translate = false`，自动按「仅原文」执行。

### 音频 URL 下载网络策略
- 自动兼容公网地址、自定义 HTTP(S) 端口、**VPN Fake-IP**（`198.18.0.0/15`、`fc00::/18`）及系统 HTTP 代理。
- DNS 多记录自动过滤有效授权地址，兼容含空格或中日文字符的音频 URL 与重定向。
- 默认允许已鉴权任务访问局域网与 Tailscale（`100.64.0.0/10`）内网音源，也可在「设置 → 网关与音频来源」中关闭并改用多来源白名单；本机回环、本机网卡与链路本地地址始终严格拦截。
- 同步上传音频上限 8 MiB / 5 分钟；Kikoeta 单条音轨下载上限 1 GiB / 20 分钟。局域网监听支持「仅本机」「IP 白名单（IPv4/IPv6/CIDR）」「局域网全开」三种模式。

---

## 构建与测试

工程固定使用 **Gradle 9.1.0、AGP 9.0.1、Android SDK 36 与 JDK 17**，在仓库根目录执行：

```powershell
./Stage-PinnedRuntime.ps1
gradle :app:verifyPinnedRuntime :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

- `Stage-PinnedRuntime.ps1` 自动拉取 [llama.cpp b12470](https://github.com/ggml-org/llama.cpp/releases/tag/b12470) 的 Snapdragon Android arm64 运行时并核对 17 项文件 SHA-256（已有归档可传 `-Archive`）。GGUF 模型与原生库均不纳入 Git 源码。
- GitHub Actions（[.github/workflows/android.yml](.github/workflows/android.yml)）同时验证 Windows Debug APK / 单测 / Lint 构建与基于固定 commit（`dde9de82c`）和容器摘要的独立 arm64 原生编译。

### 真机安装与仪器测试

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -t -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e backend CPU dev.workspace.qwen.asr.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e backend HTP0 dev.workspace.qwen.asr.test/androidx.test.runner.AndroidJUnitRunner
```

- 真机验收记录见 [VERIFICATION.md](VERIFICATION.md)，架构设计说明见 [HANDOFF.md](HANDOFF.md)。
- 真实 SDK 契约验证使用 OpenAI Python SDK 2.30.0 与 DashScope Python SDK 1.25.13，通过 `GatewayDeviceTest#serveSdkFixtures` 与 `python tests/verify_sdks.py <桌面端口>` 运行。
  [OpenAI Audio API](https://platform.openai.com/docs/api-reference/audio/createTranscription) · [阿里云百炼 ASR API](https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference)
