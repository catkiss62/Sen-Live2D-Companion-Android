# Sen Live2D Companion Android

Sen 专用的 Android Live2D AI 伴侣实验项目。它与“迷梦”项目完全分离，避免不同模型的参数、外观预设和调试结论互相污染。

## 当前版本：v0.2.0 Android 原生渲染测试

第一阶段只验证一件事：使用 Live2D 官方 Cubism SDK for Java 5 R5 的 Android 原生 OpenGL 渲染，让 Sen 的静态显示与 VTube Studio 一致。

- 导入用户本机的 Sen 模型 ZIP，不将模型写入仓库或 APK。
- 自动扫描 ZIP 中未登记的 `.exp3.json` 并补写到 App 私有目录内的 `model3.json` 副本。
- 自动读取 `.vtube.json` 的 `SavedActiveExpressions`；当前 Sen 包会恢复 `Watermark` 表情来隐藏提示水印。
- 可在“VTS 启动状态”和“原始状态”之间重载对照。
- 下方约 1/3 固定测试面板列出 ZIP 表情，预览区保持在上方约 2/3。
- 默认使用原始 2K 贴图；贴图逐张解码上传，不生成 mipmap，避免额外约三分之一显存占用。
- 关闭官方示例的 MOC consistency 重复检查，避免在创建139 MiB moc3时再次复制整份文件。
- 不再使用 WebView、JavaScript、WebAssembly 或联网 Core；渲染和 Core 模型创建都在 Android 原生进程完成。
- 使用 App `largeHeap` 为首次创建超大型模型保留空间，并显示 model3、moc3、表情、物理和每张贴图的原生加载进度。

静态显示通过后，才会继续移植 VTube Studio 九轴/物理、桌宠随机动作、DeepSeek 对话、TTS 口型和摸头反应。

## APK 使用

1. 在 GitHub Actions 下载 `Sen-Live2D-Renderer-Test-APK`。
2. 安装 APK，点“导入ZIP”，选择你购买的 `Sen Customizable Model_2K.zip`。
3. App 已包含许可允许再分发的官方 `Live2DCubismCore.aar`，不需要联网或单独导入 Core。
4. 首次导入要解压约 139 MiB 的 moc3 和 26 张 2K 贴图，随后由原生 OpenGL 逐步加载，耗时与手机存储速度有关。

## 仓库边界

本仓库和构建产物不包含：

- 用户购买的 Sen 模型、贴图、表情、动作或 VTube Studio 配置；
- VTube Studio 本体或其反编译代码。

仓库以 submodule 固定使用 Live2D 官方 `CubismJavaFramework` 5-r.5，并按官方 `RedistributableFiles.txt` 收录 `Live2DCubismCore.aar`。相关条款见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与 `Core/LICENSE.md`。开发交接与实机确认记录见 [docs/SEN_HANDOFF.md](docs/SEN_HANDOFF.md)。
