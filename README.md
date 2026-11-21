# 《重返未来：1999》英文字幕辅助工具 (Reverse: 1999 Live English Captions)

一款面向《重返未来：1999》国服玩家的 Android 实时英文字幕悬浮工具，基于开源项目 [LiveCaptionN](https://github.com/chartmann1590/LiveTranscribe-Android)（MIT License）二次开发定制。

> **免责声明 / 版权声明**：本项目仅用于个人英语听力学习与开源技术交流，完全免费、无广告、不盈利。游戏中所有角色名、世界观、台词文本与配音著作权均归原版权方（深蓝互动 / Bluepoch）所有；如版权方认为仓库内任何学习笔记文本存在不妥，请通过 GitHub Issue 或 DMCA 联系，确认后将立即删除相关文本文件。

---

## 核心特性（相比原版的主要改进）

1. **多口音免费离线识别引擎（Sherpa-ONNX Whisper Small + Silero VAD）**
   - 内置 `whisper-small.en (int8)` 与 `silero_vad.int8.onnx`，纯本地离线识别游戏系统音频（MediaProjection），针对《重返未来：1999》多国口音和背景音乐场景大幅提升准确率。
   - 保留 `Vosk` 流式引擎并解除大模型本地使用限制。
2. **服务总开关 & 资源彻底释放**
   - 主页提供一键「字幕服务总开关」；关闭开关后停止音频采集、退出前台服务并主动释放 ONNX / VAD / Vosk 原生模型内存，不玩游戏时不占系统资源。
3. **电影/游戏字幕条悬浮窗**
   - 支持横屏极简字幕条模式：隐藏常驻顶栏按钮、字体 `10–32sp`（默认 `14sp`）、背景透明度 `0%–100%`（支持纯文字 + 黑色描边投影）、仅保留最近 `1–6` 行（默认 `2` 行），并支持 **锁定防误触（触摸穿透）**。
4. **章节感知三层顺序窗口匹配 + 自动跨章推进**
   - 支持直接导入双语对照 Markdown（如 `story-notes/story_bilingual_reference.md`）或纯英文 TXT（`story-notes/story_en_for_app.txt`）。
   - 自动按 `## 第 X 章` 分章并补充长句拆句索引；通过「顺序窗口 → 章内重定位 → 防倒跳全局回退」消歧，**跨入下一章时自动切换并保存进度**。
5. **UTTU 在线选章 & 可选大模型上下文纠偏**
   - 内置 UTTU 剧情目录浏览器，支持分类浏览/拉取剧情；若无英文原稿，可选配阿里云百炼（`qwen3.8-omni-flash` / `qwen-turbo`）或任意 OpenAI 兼容接口做极省 token 的滑窗上下文纠偏。

---

## 快速使用（手机端）

**Release 提供两个版本：**
- **轻装版 / 更新包 `story-english-captions-lite-v1.0.7.apk`（`~135 MiB`，推荐更新及省流量使用）**：只内置 VAD 检测模型，首次打开可在首页一键从国内镜像下载 Whisper 模型（支持 `Small` 推荐 / `Base` 轻量 / `Tiny` 极速），或直接导入本地 `.zip` 模型包；模型存于私有持久目录，后续更新 APK 无需重新下载大模型。
- **完整开箱版 `story-english-captions.apk`（`~533 MiB`）**：内置完整 `whisper-small.en` 与 Vosk 小模型，装完免配置即刻开用。

**使用步骤：**
1. 下载安装对应版本的 APK（`arm64-v8a`，Android 9+）。
2. （可选，强烈推荐）将仓库中 `story-notes/story_bilingual_reference.md` 传到手机，在 App 内「剧情台词库」卡片点击 **导入 MD/TXT** 并选择该文件（此时无需配置任何大模型 API Key，0 token 纯离线匹配）；也可直接使用 **UTTU 在线选章**。
3. 授予悬浮窗与屏幕/音频捕获权限，打开顶部 **「字幕服务总开关」**（默认 `System Audio` + `Whisper`），进入游戏剧情即可使用。

---

## 从源码构建

由于 GitHub 限制单文件不超过 100 MB，仓库不包含大型 `.onnx` 模型权重与 `.aar` 二进制。构建前请先运行脚本拉取依赖（需网络访问 GitHub / HF-Mirror）：

```powershell
# Windows PowerShell
.\scripts\download_deps.ps1
.\gradlew.bat :app:testGithubLiteDebugUnitTest :app:assembleGithubLiteDebug
# 或编译内置大模型的完整包：
.\gradlew.bat :app:assembleGithubFullDebug
```

输出 APK 分别位于 `app/build/outputs/apk/githubLite/debug/` 与 `app/build/outputs/apk/githubFull/debug/`。

---

## 开源致谢与许可

- 上游项目：[LiveTranscribe-Android (LiveCaptionN)](https://github.com/chartmann1590/LiveTranscribe-Android) — MIT License
- 语音推理与 VAD：[k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0）、OpenAI Whisper（MIT）、Silero VAD（MIT）、AlphaCephei Vosk（Apache-2.0）
- 详见 [LICENSE](LICENSE) 与 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
