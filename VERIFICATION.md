# 重构验收记录

2026-11-18 至 2026-11-19，香港时间。版本 0.2.0-dev，包名 `dev.workspace.qwen.asr`。测试机为 SM8850、Android API 37。

## 已验证

| 项目 | 结果 |
| --- | --- |
| 构建 | Gradle debug APK、Android 测试 APK、JVM 契约测试（5 项）和 Android Lint 通过。Lint 有非阻塞警告。 |
| 运行时固化 | 验证上游 b12470 Snapdragon arm64 归档与打包清单中 17 项文件的 SHA-256。APK 原生包由此暂存。 |
| 同签名升级 | 0.1.1 → 0.2.0-dev 覆盖升级成功。旧 1.7B 与 mmproj 按固定清单核对 SHA-256 后原地登记。原目标语种保留。 |
| 1.7B 端侧推理 | CPU 音频 → CPU 文本和 CPU 音频 → HTP0 文本都识别出了合成英语短音频。两套组合均通过 OpenAI multipart、百炼同步 Base64 回归。 |
| 0.6B 模型分发 | 手机通过当前 Wi-Fi 连接 Hugging Face 超时。桌面已通过固定 revision 下载 804,749,248 与 214,392,480 字节的配套文件，两份 SHA-256 均匹配。 |
| 0.6B 导入与推理 | 通过应用的配套导入逻辑校验并安装。CPU→CPU、CPU→HTP0 均通过短音频、OpenAI multipart 和百炼同步 Base64 用例。 |
| Kikoeta 网关 | 真机绑定 `127.0.0.1:2370` 与 `[::1]:2370`。模型预热期间 Basic 鉴权的健康检查成功。仪器测试核对缓存响应、端口占用时保留监听和协议关闭。 |
| SDK | OpenAI Python SDK 2.30.0 的模型列表和文件转写通过。DashScope Python SDK 1.25.13 同步 `MultiModalConversation.call` 通过。客户端经临时 ADB 回环转发访问手机。 |
| 离线翻译 | 同一设备通过 Wi-Fi 安装 ML Kit 日语和中文包。英→中、日→中、生成含两种源文本及译文的 SRT 均通过。该用例直接提交文本给端侧翻译器。 |

## 截图和日志

真机采集内容在 `build/handoff-verification/`。`02-upgraded-live.png` 显示旧模型已识别、离线包缺失的首页，`04-listening.png` 显示实际回环地址和模型预热就绪。两档模型、SDK 和离线翻译的独立测试日志保存在同一目录。

长音频回归在 SM8850 上使用 0.6B、CPU 与 2048 tokens。6 分 12 秒的合成 WAV 分段解码完整，并识别出第 6 分钟的语音。网关契约及相邻片段语种回退同时通过。日志见 `long-audio-instrumentation.txt`。

Kikoeta HTTP 408 修复在 Android API 36 模拟器通过三项网关测试。空事件请求等待期间健康查询和取消仍可完成，延迟 4.5 秒发送的请求体可读取，空闲连接关闭时不返回 408。日志见 `kikoeta-408-emulator.txt`。真机继续运行原转写任务，测试 APK 仅装入临时模拟器。

## 发布前仍待验收

- 非 Hexagon 的 arm64 设备、GPU 路径和独立音频编码器卸载。当前 mmproj 使用 CPU。
- 飞行模式下日语和英语音频到双语 SRT 的完整路径，长时采集、峰值内存和掉段基准。
- 局域网真实客户端、白名单拒绝及 HyperOS 切回 Kikoeta 后的 60 秒回归。
- 完整首装与录音、播放捕获、双语出字的真机截图组。TalkBack 和 200% 字号还需真机验收。
- 目标网络上的模型直连下载与断点续传。当前手机可使用已校验的模型文件走导入流程。
- CI 原生源码构建的远端实跑、发布签名及可再分发审查。DashScope 实时 WebSocket 和异步接口尚未纳入当前实现，待确定目标客户端后补齐。
