# Qwen 听写

Android 端侧听写、翻译和字幕应用。工程以 0.1.1 为升级起点，目标与验收细节见 [HANDOFF.md](HANDOFF.md)。

## 使用

1. 打开「实时翻译」。新安装从「配置模型」下载或导入一套 Q8_0 主模型和匹配的 mmproj。存量安装会按 SHA-256 原地识别旧文件。安装资产后可在「推理」运行短音频自检，自动档优先使用本机已验证的 HTP0，其余情况使用 CPU。
2. 在「翻译」选源与目标语种。默认使用 ML Kit 端侧包，缺包时可听写并导出原文。联网翻译需填写服务并明确启用，原有联网参数仍可重新选用。语种和推理变更在重新加载模型时生效。
3. 选择麦克风或播放音频并点「开始」。系统在使用相应音源时请求授权。字幕选项可切换双语、原文和译文，开启悬浮字幕或导出 SRT。
4. 需要接入其他应用时，在「服务」启动 API 监听。默认只监听本机，Kikoeta 地址仍为 `127.0.0.1:2370`，使用 Basic 账号 `admin` 和服务页复制的现有密码。缓存查询与结果下载可在模型预热期间访问。

## API

| 协议 | 默认 | 契约 |
| --- | --- | --- |
| Kikoeta | 开启 | `/api/v1/health`、任务、事件、缓存、暂停／恢复、取消和私有 LRC 结果。 |
| OpenAI Audio | 手动开启 | `/v1/models`、`/v1/audio/transcriptions`，Bearer，multipart。支持 `file`、`model`、`language`、`json` 和 `text`。 |
| 百炼 DashScope | 手动开启 | `/api/v1/services/aigc/multimodal-generation/generation`，Bearer，接受 `input.messages[].content[].audio` 中的 Base64 Data URI 或公开 HTTP(S) 音频。可配置云模型别名。 |

本地模型 ID 为 `qwen3-asr-0.6b-q8_0` 与 `qwen3-asr-1.7b-q8_0`。同一时间预热一套模型，需从模型页切换其他已安装的档位。同步上传音频上限为 8 MiB 和 5 分钟。局域网监听须选择设备接口，可填写 IPv4/IPv6 白名单或显式允许任意来源。使用外部 TLS 代理时，可让网关继续绑定本机，由代理处理 TLS 和外部访问范围。音频下载自动支持公网、VPN Fake-IP（`198.18.0.0/15`）、系统 HTTP 代理与自定义端口，内网 / Tailscale 音源可在「服务配置」中统一开关或按来源白名单授权。

Kikoeta 批量任务按约 8 秒分段，支持在服务页或服务配置中即时切换输出文本为「仅原文（默认）」「双语」或「仅译文」。单条音轨下载上限为 1 GiB，最长 20 分钟。翻译无法判定个别片段的语种时会保留该片段原文，其他字幕继续生成。

## 构建与测试

版本固定为 Gradle 9.1.0、AGP 9.0.1、Android SDK 36 和 JDK 17。以下命令从仓库根目录运行。使用同签名的 APK 才能覆盖既有安装。

```powershell
./Stage-PinnedRuntime.ps1
gradle :app:verifyPinnedRuntime :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

脚本下载 llama.cpp b12470 的 Snapdragon Android arm64 发布包，验证归档与各待打包库的 SHA-256。若已下载归档，可传入 `-Archive`。CI 同时包含基于上游 commit 和容器摘要固定的独立原生构建，并输出 CMake 配置及制品摘要。GGUF 与编译后的运行时均放在仓库源码之外。[llama.cpp b12470](https://github.com/ggml-org/llama.cpp/releases/tag/b12470)

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -t -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e backend CPU dev.workspace.qwen.asr.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e backend HTP0 dev.workspace.qwen.asr.test/androidx.test.runner.AndroidJUnitRunner
```

短音频和协议测试需要目标设备已安装 1.7B 模型与配套 mmproj。APK 支持 Android 13 及以上。GPU 与 HTP 的实际可用性由应用 UID 下的运行时枚举和短音频测试确认。

真机测试结果见 [VERIFICATION.md](VERIFICATION.md)。

真实 SDK 测试使用 OpenAI Python SDK 2.30.0 和 DashScope Python SDK 1.25.13。以 `sdkPort` 参数运行 `GatewayDeviceTest#serveSdkFixtures`，通过 ADB 转发临时端口，再运行 `python tests/verify_sdks.py <桌面端口>`。用例使用临时测试凭据和合成短音频。 [OpenAI Audio 契约](https://platform.openai.com/docs/api-reference/audio/createTranscription) · [百炼同步 ASR 契约](https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference)
