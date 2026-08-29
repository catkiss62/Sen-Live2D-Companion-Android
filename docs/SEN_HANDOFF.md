# Sen Live2D Android 对接记录

> 维护规则：每轮先在“待实机确认”写入已经制作但尚未确认的内容；用户确认后，再移入“实机已确认”。任何新发现都应追加到本文件，避免换对话或换 AI 后重复踩坑。

## 当前目标

先让 Sen 模型在 Android 静止显示时与 VTube Studio 一致。头发、眼睛、剪贴蒙版、绘制顺序与水印状态全部确认以前，不加入九轴、自主动作、LLM 情绪与 TTS。

## 已确认的模型事实

- 用户提供的是 `Sen Customizable Model_2K`，模型不能提交到公开仓库或打入 APK。
- `.moc3` 使用 Cubism 5；旧项目的 `pixi-live2d-display/cubism4` 不是合适的渲染底层。
- ZIP 中有 26 张 2048 贴图。原始 PNG 合计约 18 MiB，但上传 WebGL 后约占 `26 × 2048 × 2048 × 4 ≈ 416 MiB`，还不含 Cubism 蒙版和缓冲区。第一阶段先验证原始2K；实机出现“无错误但全黑”后，新增不覆盖原文件的1K兼容副本用于判断显存问题。
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

### v0.1.4-black-screen-fallback

- [ ] 监听 `webglcontextlost`，不再让 WebView 在显存上下文丢失后静默停在黑屏。
- [ ] 模型完成后对首帧进行网格采样，显示画布尺寸、模型画布尺寸以及可见采样点；可区分“已绘制但位置不对”和“整张画布确实全黑”。
- [ ] 原始2K发生上下文丢失或首帧全黑时，自动生成并切换1K兼容贴图；原始2K模型、贴图和购买文件保持不变。
- [ ] 测试面板可手动在“原始2K / 1K兼容”之间切换，方便确认手机与 WebView 的实际承载上限。

### v0.1.3-safe-touch

- [ ] 修复用户松开手指时，精简舞台未创建官方示例齿轮对象却调用 `null.isHit()` 的错误。
- [ ] 齿轮等示例 UI 被删除后仍可正常触摸模型，点击模型只进入模型自身的点击逻辑。
- [ ] 错误标题由容易误判的“渲染失败”改为“舞台运行失败”；触摸、加载、释放阶段的 JavaScript 错误不再一律归因于渲染器。

### v0.1.2-safe-reload

- [ ] 修复页面重载时背景/齿轮占位资源尚未创建却调用 `null.release()` 的错误。
- [ ] View、Subdelegate、Delegate 的资源释放流程允许空值并可重复调用。
- [ ] 旧页面退出阶段的异常不再覆盖新页面的真实加载进度。

### v0.1.1-renderer-diagnostics

- [ ] 修复26张2K贴图同时解码造成的内存峰值：保持2K原图，改为逐张解码和上传。
- [ ] 贴图上传后释放 CPU 端解码图片，不生成移动端当前视图不需要的 mipmap。
- [ ] 状态栏能分别显示 model3、约139 MiB moc3、模型创建、表情/物理以及贴图 `n/26`。
- [ ] 任一贴图解码或 WebGL 上传失败时，显示具体编号和错误，不再永久停在“正在加载”。

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

## 踩坑点（后续 AI 必读）

### 官方示例对象并不一定存在

- 本项目只移植官方 Cubism 示例的模型渲染部分，背景图、齿轮按钮等示例 UI 会被省略。因此，官方源码里对 `_back`、`_gear` 等对象的直接调用不能原样保留。
- 已遇到两种同源崩溃：页面重载/退出时的 `null.release()`，以及触摸结束时的 `null.isHit()`。前者属于资源释放，后者属于触摸交互，都不是 Sen 模型文件或 Cubism 渲染失败。
- 以后修改固定版本的官方 Sample 后，必须搜索被省略对象的全部成员调用，而不只修复当前堆栈中的一行：`rg "_back\\.|_gear\\." lappview.ts`。初始化、渲染、触摸和释放四条路径都要允许对象为空。
- 页面卸载阶段可能与新页面加载重叠。卸载错误不得覆盖新页面状态；释放函数必须可重复调用，且每个资源在调用 `release()` 前都要判空。
- Android 侧统一错误标题使用“舞台运行失败”。判断原因时以最后一条加载进度和具体属性名为准，不要看到统一标题就擅自降低贴图或怀疑 `.moc3`。

### 无报错黑屏不等于模型损坏

- 官方 `LAppSubdelegate.update()` 发现 `gl.isContextLost()` 后会直接 `return`，默认既不通知 Android，也不抛 JavaScript 错误。因此，“上半区纯黑、没有报错”必须优先检查 WebGL 上下文，而不能继续修 `release/isHit` 或怀疑 ZIP。
- PNG 的磁盘体积不能代表显存体积。Sen 的26张2K透明贴图即使压缩包很小，RGBA 上传后仍约占416 MiB；Cubism 的剪贴蒙版、帧缓冲和模型资源会继续增加峰值。
- 1K兼容模式只在 App 私有目录生成替代贴图和一个替代 `model3.json`，复用原始 `.moc3`、物理、表情和动作；不得覆盖或修改用户购买的原始2K贴图。
- 若1K首帧采样仍全黑，则下一步检查模型矩阵、绘制参数和默认部件状态；不要继续无条件降到更低分辨率。

## 下一阶段（静态显示通过后）

1. 解析 `.vtube.json` 的 62 个 ParameterSettings，移植 VTube Studio 的输入范围、输出范围和平滑。
2. 对比并移植 VTS 物理参数，再恢复触屏九轴跟随。
3. 识别并接入模型自带 `daiji.motion3.json` 与适合桌宠的随机动作。
4. 重新接入 DeepSeek 对话、情绪标签、TTS 口型和摸头反应。
