# Sen Live2D Companion Android

Sen 专用的 Android Live2D AI 伴侣实验项目。它与“迷梦”项目完全分离，避免不同模型的参数、外观预设和调试结论互相污染。

## 当前版本：v0.3.5 耳鳍位置与呆毛层级测试

第一阶段只验证一件事：使用 Live2D 官方 Cubism SDK for Java 5 R5 的 Android 原生 OpenGL 渲染，让 Sen 的静态显示与 VTube Studio 一致。

- 导入用户本机的 Sen 模型 ZIP，不将模型写入仓库或 APK。
- 自动扫描 ZIP 中未登记的 `.exp3.json` 并补写到 App 私有目录内的 `model3.json` 副本。
- 另行导入电脑采集器输出的 `Sen.vts-profile.json` 与当前 `.vtube.json`；文件只保存到 App 私有目录，不进入仓库或 APK。
- 冻结模式一次性写入VTS采集的全部581项最终值，并叠加 `.vtube.json` 的79项ArtMesh乘算/屏幕色；停用表情、物理和每帧更新，只验证相同静态状态下的底层显示。
- VTS专用 `VTS_Add` 不能再近似成普通`Add`。冻结模式通过Core ParameterView保留 `Warning2=-1` 等超出模型声明范围的实际VTS值。
- 可在“冻结VTS采集状态”“旧分层状态”和“原始状态”之间重载对照；旧分层状态仅用于证明v0.3.1差异，不是当前正确方案。
- 预览区可开启调整模式：单指拖动、双指缩放，并可还原位置与大小。
- C高精度蒙版已解决Sen的60组蒙版溢出导致的白块、缺刘海、嘴、手指和耳朵错层；默认使用1024蒙版，并保留256/512用于边缘对照。
- 耳鳍角度提供`-50°～+30°`左右联动测试，负数为向外放平；另有独立上下位置滑杆，负数向下，整个`Part56（鱼鳍耳）`子层级一起移动。
- 长呆毛测试通过Cubism Part父子层级自动查找当前可见子Drawable，不再把CDI中的蒙皮Part误当作Drawable ID。
- 下方约 1/3 固定测试面板列出 ZIP 表情，预览区保持在上方约 2/3。
- 默认使用原始 2K 贴图；贴图逐张解码上传，不生成 mipmap，并通过主仓库补丁把官方 Framework 的每帧过滤同步改为 `GL_LINEAR`，避免无 mipmap 贴图被采样成纯黑。
- 关闭官方示例的 MOC consistency 重复检查，避免在创建139 MiB moc3时再次复制整份文件。
- 不再使用 WebView、JavaScript、WebAssembly 或联网 Core；渲染和 Core 模型创建都在 Android 原生进程完成。
- 使用 App `largeHeap` 为首次创建超大型模型保留空间，并显示 model3、moc3、表情、物理和每张贴图的原生加载进度。

冻结快照只用于把“状态初始化错误”和“底层渲染兼容错误”分开，不会成为最终生产架构。静态外观实机确认后再继续设计永久外观层、九轴/物理、桌宠随机动作、DeepSeek对话、TTS口型和摸头反应。

## APK 使用

1. 在 GitHub Actions 下载 `Sen-Live2D-Renderer-Test-APK`。
2. 安装 APK，点“导入ZIP”，选择你购买的 `Sen Customizable Model_2K.zip`。
3. 点“导入外观”，同时选择 `Sen.vts-profile.json` 与当前的 `Sen Customizable Model_2K.vtube.json`；文件选择器不支持多选时可分两次导入。
4. 点“冻结VTS采集状态”，重点检查右上白块、刘海、嘴部、左右手指和白色丝袜透明度。
5. 需要放大时先点“调整模型：关闭”使其变为开启，再单指拖动、双指缩放；完成后关闭调整避免误触。
6. App 已包含许可允许再分发的官方 `Live2DCubismCore.aar`，不需要联网或单独导入 Core。
7. 首次导入要解压约 139 MiB 的 moc3 和 26 张 2K 贴图，随后由原生 OpenGL 逐步加载，耗时与手机存储速度有关。

从 v0.2.0 起仓库固定使用公开测试签名，后续测试 APK 可以直接覆盖更新。由于 v0.1.x 使用过临时构建签名，首次安装 v0.2.0 若提示签名不一致，需要卸载旧版后安装并重新导入一次模型 ZIP。

## 仓库边界

本仓库和构建产物不包含：

- 用户购买的 Sen 模型、贴图、表情、动作或 VTube Studio 配置；
- VTube Studio 本体或其反编译代码。

仓库以 submodule 固定使用 Live2D 官方 `CubismJavaFramework` 5-r.5，并按官方 `RedistributableFiles.txt` 收录 `Live2DCubismCore.aar`。相关条款见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与 `Core/LICENSE.md`。

**新对话或新 AI 必须先阅读唯一任务事实源：[docs/SEN_TASK_LEDGER.md](docs/SEN_TASK_LEDGER.md)。** 每轮制作、实机确认、失败结论、踩坑和下一步顺序都维护在其中。
