# Qwen 听写

Android 伴侣应用，可为同一部手机上的 Kikoeta 生成中文字幕，也可以采集麦克风或其他应用允许捕获的播放音频，显示原文与译文，导出 SRT。

下一版的产品范围、页面规格、接口契约与真机验收见 [重构交接文档](HANDOFF.md)。

## 开始使用

1. 安装 arm64 APK。在「模型与翻译设置」中分别导入 `Qwen3-ASR-1.7B-Q8_0.gguf` 和配套的 `mmproj-Qwen3-ASR-1.7B-Q8_0.gguf`。应用会将它们复制到私有目录，确保能够由 llama.cpp 读取和 mmap。
2. 如需翻译，填写提供 OpenAI 兼容 `/v1/chat/completions` 的服务地址、模型 ID 和服务所需的 API Key。服务可以是另一个本机模型，也可以是 HTTPS 服务。ASR 使用端侧模型，翻译接口收到的是识别后的文本。
3. 返回主界面，点「启动服务」。音频字幕可选「麦克风」或「播放音频」。播放捕获会弹出系统授权，支持允许捕获的媒体音频。采集期间可以开启悬浮字幕，结束后可导出原文或双语 SRT。
4. 在 Kikoeta 的「更多 → 听写翻译」选择本地连接，填写 `127.0.0.1:2370` 和本应用显示的账号、密码。配置翻译后，可以从 Kikoeta 的作品详情页提交音轨，完成后客户端会导入简体中文 LRC。当前版本在转写后翻译并直接生成 LRC，校正未单列为一个推理步骤。

Kikoeta 可以使用现有的任务查询、事件轮询、取消、暂停、恢复、缓存和字幕下载接口。任务仅在服务进程内保留，已完成的作品字幕缓存保存在应用私有目录。暂停在当前音频段或下载检查点生效。

点「启动服务」后，Kikoeta 可以在模型加载期间同步已有字幕。创建新听写任务需等到 Qwen 听写显示「服务已启动」。

在 HyperOS 上，如果切到 Kikoeta 后连接中断，请在系统应用管理里把「Qwen 听写」的省电策略设为「无限制」，然后重新启动听写服务。Kikoeta 的服务地址仍填 `127.0.0.1:2370`。

## 推理与字幕

- APK 内的 `llama-server` 随前台服务启动一次，使用 `--device HTP0 -ngl 99 --no-mmproj-offload -c 2048 --parallel 1`。语言模型层使用 HTP，音频编码器使用 CPU。可通过 `adb logcat -s QwenLlama` 核对设备与张量卸载日志。
- 也可填写一个已启动的 llama.cpp 服务地址供应用连接，该模式通常用于开发时替换推理栈。ASR 首选 `/v1/audio/transcriptions`，旧版没有该接口时使用带音频的 `/v1/chat/completions`。
- 实时字幕按三秒音频段识别，批量任务按八秒段处理。字幕采用段级时间戳。实时字幕会先显示原文，等翻译返回后再补上译文。处理跟不上采集时，应用跳过较旧的等待片段并提示。

在 SM8850 真机上，APK 以普通应用进程加载了 Hexagon v81，日志记录语言模型卸载 `29/29` 层，音频编码器在 CPU 上。用 Kikoeta 的请求格式验证了任务提交、事件、LRC 下载与缓存，也验证了播放音频转写。翻译响应使用测试服务。

## 构建

从 `D:\Android Dev` 运行。以下使用手机上已验证的 llama.cpp Snapdragon Android 包。也可以把同一套完整运行时下载到本机后，给脚本传 `-LocalRuntime`。

```powershell
. .\scripts\env.ps1
.\qwen-asr-android\Stage-Hexagon.ps1 -DeviceRuntime '/sdcard/Download/llama.cpp/llama.cpp' -Serial '<设备序列号>'
gradle --offline -p .\qwen-asr-android :app:assembleDebug
adb install -r .\qwen-asr-android\app\build\outputs\apk\debug\app-debug.apk
```

运行时包含 arm64 的 llama.cpp 与对应的 HTP 库，须来自同一个构建。应用支持 Android 13 及以上，内置 HTP 路径取决于手机的 Snapdragon 芯片、系统及 FastRPC 支持。APK 与源码分开存放模型。构建过程使用 Gradle 9.1.0、Android SDK 36 和 JDK 17。

参考资料可查阅 [llama.cpp Snapdragon 后端](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/snapdragon/README.md)、[Qwen3-ASR GGUF](https://huggingface.co/ggml-org/Qwen3-ASR-1.7B-GGUF) 和 [Kikoeta Transl](https://github.com/chenflxs/kikoeta-transl)。
