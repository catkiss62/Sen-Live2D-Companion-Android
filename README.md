# Sen Live2D Companion Android

Sen 专用的 Android Live2D AI 伴侣实验项目。它与“迷梦”项目完全分离，避免不同模型的参数、外观预设和调试结论互相污染。

## 当前版本：v0.1.4 黑屏诊断与安全画质回退

第一阶段只验证一件事：使用 Live2D 官方 Cubism Web Framework 5.3，让 Sen 在 Android 中的静态显示与 VTube Studio 一致。

- 导入用户本机的 Sen 模型 ZIP，不将模型写入仓库或 APK。
- 自动扫描 ZIP 中未登记的 `.exp3.json` 并补写到 App 私有目录内的 `model3.json` 副本。
- 自动读取 `.vtube.json` 的 `SavedActiveExpressions`；当前 Sen 包会恢复 `Watermark` 表情来隐藏提示水印。
- 可在“VTS 启动状态”和“原始状态”之间重载对照。
- 下方约 1/3 固定测试面板列出 ZIP 表情，预览区保持在上方约 2/3。
- 默认使用原始 2K 贴图，不在这一阶段生成降质版本。
- 大型 moc3 与 26 张贴图提供分阶段进度；贴图逐张解码上传，降低瞬时内存占用。
- 精简官方示例 UI 后，对背景、齿轮等可选对象的触摸与释放路径做空值保护。
- 监听 WebGL 显存上下文丢失并检查首帧是否确实可见；原始2K黑屏时可生成不覆盖原文件的1K兼容副本。

静态显示通过后，才会继续移植 VTube Studio 九轴/物理、桌宠随机动作、DeepSeek 对话、TTS 口型和摸头反应。

## APK 使用

1. 在 GitHub Actions 下载 `Sen-Live2D-Renderer-Test-APK`。
2. 安装 APK，点“导入ZIP”，选择你购买的 `Sen Customizable Model_2K.zip`。
3. App 默认联网读取 Live2D 官方 Cubism Core。如需离线，点“导入Core”选择你合法取得的 `live2dcubismcore.min.js`。
4. 首次导入要解压约 139 MiB 的 moc3 和 26 张 2K 贴图，耗时与手机存储速度有关。

## 仓库边界

本仓库和构建产物不包含：

- 用户购买的 Sen 模型、贴图、表情、动作或 VTube Studio 配置；
- Live2D Cubism Core；
- VTube Studio 本体或其反编译代码。

Web 阶段在构建时固定拉取 Live2D 官方 `CubismWebSamples` / `CubismWebFramework`，相关条款见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。开发交接与实机确认记录见 [docs/SEN_HANDOFF.md](docs/SEN_HANDOFF.md)。
