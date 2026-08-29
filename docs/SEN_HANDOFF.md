# Sen Live2D Android 对接记录

> 维护规则：每轮先在“待实机确认”写入已经制作但尚未确认的内容；用户确认后，再移入“实机已确认”。任何新发现都应追加到本文件，避免换对话或换 AI 后重复踩坑。

## 当前目标

先让 Sen 模型在 Android 静止显示时与 VTube Studio 一致。头发、眼睛、剪贴蒙版、绘制顺序与水印状态全部确认以前，不加入九轴、自主动作、LLM 情绪与 TTS。

## 已确认的模型事实

- 用户提供的是 `Sen Customizable Model_2K`，模型不能提交到公开仓库或打入 APK。
- `.moc3` 使用 Cubism 5；旧项目的 `pixi-live2d-display/cubism4` 不是合适的渲染底层。
- ZIP 中有 26 张 2048 贴图，模型精度和资源占用都很高；第一阶段坚持使用原始 2K，不生成 1K，避免把贴图降级与渲染错误混淆。
- 原始 `model3.json` 没有登记 `FileReferences.Expressions`，但 ZIP 根目录中实际存在多个 `.exp3.json`。导入器必须扫描并补登记，官方 Framework 才会加载这些表情。
- `.vtube.json` 的 `SavedActiveExpressions` 当前包含 `Watermark.exp3.json`。这个表情把 `Warning` 至 `Warning6` 参数设为 `-1`，作用是关闭授权提示水印，不应被排除。
- VTube Studio 的参数映射、输入输出范围、平滑、物理强度等保存在 `.vtube.json` 中；这些将在静态显示通过后再移植。

## 技术方案

- Android 原生壳 + `WebViewAssetLoader`。
- WebView 内使用 Live2D 官方 Cubism Web Framework 5.3 兼容版本，不使用 Pixi Cubism 4 插件。
- 构建时固定拉取 `CubismWebSamples` commit `b1de66b...`，其 Framework submodule 固定为 `d4da0aa...`。
- 仓库和 APK 都不包含 Cubism Core。运行时优先使用用户导入的 `live2dcubismcore.min.js`，没有时才访问 Live2D 官方 Core 地址。
- 模型 ZIP 只解压到 App 私有目录；仓库和 APK 不含模型。

## 待实机确认

### v0.1.0-renderer-test

- [ ] 官方 Cubism 5 Framework 能正确加载 Sen 的 Cubism 5 `.moc3`。
- [ ] 头发不再出现白色大块或错误叠层。
- [ ] 眼球、眼眶、眼皮的蒙版和绘制顺序与 VTube Studio 一致。
- [ ] 自动读取并应用 `SavedActiveExpressions`，Watermark 启动后消失。
- [ ] “查看原始状态”会重新载入模型但不应用 VTS 启动表情，便于对照。
- [ ] 下方 1/3 面板能列出 ZIP 中补登记的表情并单独测试。
- [ ] 本地 Core 导入和官方联网 Core 两种方式都能启动。

## 实机已确认

尚无。本节只记录用户明确反馈“效果正确/可以确认”的内容。

## 下一阶段（静态显示通过后）

1. 解析 `.vtube.json` 的 62 个 ParameterSettings，移植 VTube Studio 的输入范围、输出范围和平滑。
2. 对比并移植 VTS 物理参数，再恢复触屏九轴跟随。
3. 识别并接入模型自带 `daiji.motion3.json` 与适合桌宠的随机动作。
4. 重新接入 DeepSeek 对话、情绪标签、TTS 口型和摸头反应。

