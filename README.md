# Sen Live2D Companion Android

Sen 专用的 Android Live2D AI 伴侣实验项目。它与“迷梦”项目完全分离，避免不同模型的参数、外观预设和调试结论互相污染。

## 当前版本：v0.3.1 VTS 分层外观测试

第一阶段只验证一件事：使用 Live2D 官方 Cubism SDK for Java 5 R5 的 Android 原生 OpenGL 渲染，让 Sen 的静态显示与 VTube Studio 一致。

- 导入用户本机的 Sen 模型 ZIP，不将模型写入仓库或 APK。
- 自动扫描 ZIP 中未登记的 `.exp3.json` 并补写到 App 私有目录内的 `model3.json` 副本。
- 另行导入用户从 VTube Studio 模型目录取出的 `xiaojingyu.exp3.json` 与当前 `.vtube.json`；两个文件只保存到 App 私有目录，不进入仓库或 APK。
- 严格按“原始模型默认值 → `xiaojingyu` 的55项增量 → `.vtube.json` 的79项 ArtMesh 乘算/屏幕色”叠加，保留模型原有部件并恢复紫色头发、服装、尾巴和鞋子。
- VTS 专用的 `VTS_Add` 在本地副本中规范为 Cubism 标准 `Add`。
- 已停用 v0.3.0 的581项最终快照覆盖路线；它会把 VTS 已计算结果误当作模型底值，导致白模、部件叠错和头发异常。
- 可在“小鲸鱼完整外观”和“原始状态”之间重载对照。
- 下方约 1/3 固定测试面板列出 ZIP 表情，预览区保持在上方约 2/3。
- 默认使用原始 2K 贴图；贴图逐张解码上传，不生成 mipmap，并通过主仓库补丁把官方 Framework 的每帧过滤同步改为 `GL_LINEAR`，避免无 mipmap 贴图被采样成纯黑。
- 关闭官方示例的 MOC consistency 重复检查，避免在创建139 MiB moc3时再次复制整份文件。
- 不再使用 WebView、JavaScript、WebAssembly 或联网 Core；渲染和 Core 模型创建都在 Android 原生进程完成。
- 使用 App `largeHeap` 为首次创建超大型模型保留空间，并显示 model3、moc3、表情、物理和每张贴图的原生加载进度。

仓库仍保留电脑端 `Sen VTS Parameter Capture` 作为参数与映射诊断工具，但不再把它采集的最终参数快照作为静态外观底值。静态外观实机确认后再继续移植九轴/物理、桌宠随机动作、DeepSeek 对话、TTS 口型和摸头反应。

## APK 使用

1. 在 GitHub Actions 下载 `Sen-Live2D-Renderer-Test-APK`。
2. 安装 APK，点“导入ZIP”，选择你购买的 `Sen Customizable Model_2K.zip`。
3. 点“导入外观”，选择 `xiaojingyu.exp3.json`；再次点“导入外观”，选择当前的 `Sen Customizable Model_2K.vtube.json`。也可以在文件选择器支持时一次多选两个文件。
4. 点“应用小鲸鱼完整外观”，对照 VTube Studio 检查头发、眼睛、耳朵、服装、尾巴、鞋袜和颜色。
5. App 已包含许可允许再分发的官方 `Live2DCubismCore.aar`，不需要联网或单独导入 Core。
6. 首次导入要解压约 139 MiB 的 moc3 和 26 张 2K 贴图，随后由原生 OpenGL 逐步加载，耗时与手机存储速度有关。

从 v0.2.0 起仓库固定使用公开测试签名，后续测试 APK 可以直接覆盖更新。由于 v0.1.x 使用过临时构建签名，首次安装 v0.2.0 若提示签名不一致，需要卸载旧版后安装并重新导入一次模型 ZIP。

## 仓库边界

本仓库和构建产物不包含：

- 用户购买的 Sen 模型、贴图、表情、动作或 VTube Studio 配置；
- VTube Studio 本体或其反编译代码。

仓库以 submodule 固定使用 Live2D 官方 `CubismJavaFramework` 5-r.5，并按官方 `RedistributableFiles.txt` 收录 `Live2DCubismCore.aar`。相关条款见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与 `Core/LICENSE.md`。

**新对话或新 AI 必须先阅读唯一任务事实源：[docs/SEN_TASK_LEDGER.md](docs/SEN_TASK_LEDGER.md)。** 每轮制作、实机确认、失败结论、踩坑和下一步顺序都维护在其中。
