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

- v0.1.x 使用 Android 原生壳 + `WebViewAssetLoader`，WebView 内使用 Live2D 官方 Cubism Web Framework 5.3 兼容版本，不使用 Pixi Cubism 4 插件。
- **实机已经证明 Web 方案不适合 Sen 的超大型 `.moc3`。v0.2.0 已改用官方 Cubism SDK for Java 5 R5（Android 原生 OpenGL），不再继续围绕 WebView 黑屏打补丁。** 用户已从 Live2D 官方取得并提供完整 `CubismSdkForJava-5-r.5.zip`；Core AAR 校验完整。
- `Framework` submodule 固定到官方 `CubismJavaFramework` tag `5-r.5` / commit `c2d4200...`；APK 收录官方许可清单允许再分发的 `Live2DCubismCore.aar`，不再需要联网或用户另行导入 Core。
- 原生加载关闭 MOC consistency 重复检查；贴图逐张解码上传并立即回收 Bitmap，不生成 mipmap。App 开启 `largeHeap`，为139.2 MiB moc3的首次 Java byte[] 与 Core Model 创建留出空间。
- 模型 ZIP 只解压到 App 私有目录；仓库和 APK 不含模型。

## 待实机确认

### v0.2.0-native-renderer-test

- [ ] 使用官方 `CubismJavaFramework` 5-r.5 与 `Live2DCubismCore.aar`，不经过 WebView/JavaScript/WebAssembly，能在实机完成139.2 MiB moc3的 Core Model 创建。
- [ ] 原始26张2K贴图逐张解码上传，不生成 mipmap；加载进度能明确显示到第几张贴图。
- [ ] Sen 静态画面与 VTube Studio 对照一致：无白色头发大块、眼球/眼眶/眼皮不错误重叠、剪贴蒙版与绘制顺序正确。
- [ ] 自动应用 `.vtube.json` 的 `Watermark` 启动表情后水印消失；“查看原始状态”仍可对照水印原始状态。
- [ ] 上方约2/3为原生模型预览，下方约1/3保留 ZIP 表情测试按钮。
- [ ] App 无需联网或单独导入 Web Core；APK 内的 Java Core AAR可在用户 arm64-v8a 设备正常加载。
- [ ] v0.2.0 固定使用仓库内公开 debug keystore；从本版开始后续测试包可直接覆盖安装。v0.1.x 临时签名与本版不同时，首次迁移需卸载旧版并重新导入 ZIP。

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

- v0.1.4 导入 Sen 2K ZIP 后，上半区保持黑色，进度一直停在“moc3 已读取，正在创建 Cubism 5 模型…”。这说明程序尚未到达表情、物理、贴图解码、贴图上传或首帧采样阶段；1K兼容贴图不会触发，也不能解决当前故障。
- 此前的 `null.release()` 与 `null.isHit()` 均已不再出现，但这只代表官方示例空对象防护生效，不代表模型已经渲染成功。

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

### 停在“正在创建 Cubism 5 模型”时还没有读取贴图

- 该状态位于 `this.loadModel(arrayBuffer, ...)` 内部，主要包括把 `.moc3` 复制到 Cubism Core 内存、激活 MOC、再创建 Core Model。它是同步调用；WebView 主线程卡在这里时，后续进度、超时回调和1K自动回退都无法执行。
- Sen 的 `.moc3` 为 `145,951,936` bytes（约139.2 MiB）。使用同一官方 Cubism Core 5.1.0 在桌面进程中拆分测量：MOC 激活后进程约488 MiB，Core Model 创建后约503 MiB，尚未加载任何贴图。
- 模型规模为581个参数、229个部件、1742个 Drawable，模型画布为4234×8344。开启官方 MOC consistency 检查还会额外复制整份 `.moc3`；它不是最终内存占用的根因，但 Web 版必须关闭这次不必要的复制。
- Web Cubism Core 使用 WebAssembly 线性内存，扩容与大块复制会放大 Android WebView 渲染进程的峰值。即使随后切到1K贴图，也不能消除创建模型之前已经出现的约500 MiB压力。
- VTube Studio 能正常显示不能反证 WebView 方案可行；VTube Studio 使用原生渲染，不经过 JavaScript/WebAssembly/WebView 这套内存路径。
- 后续不要从 VTube Studio APK 拆取 Core：那既不是可维护的 SDK 来源，也可能违反其软件许可。应使用 Live2D 官方 Cubism SDK for Java 压缩包中的 Core；官方 RedistributableFiles 清单允许在许可条款下把对应运行库随 APK 分发。

### 测试 APK 必须固定签名

- GitHub 托管运行器是临时环境，不能依赖它自动生成的默认 debug keystore；否则不同构建的 APK 可能无法覆盖安装，卸载又会清除 App 私有目录里的大模型。
- v0.2.0 起使用仓库内 `app/debug.keystore`，它只用于公开测试，不是正式发布密钥。不得把它误用于商店正式版或任何需要保密身份的签名。

## 下一阶段（静态显示通过后）

1. 构建并实机验证 v0.2.0 原生 OpenGL 静态渲染。
2. 静态显示正确后，解析 `.vtube.json` 的 62 个 ParameterSettings，移植 VTube Studio 的输入范围、输出范围和平滑。
3. 对比并移植 VTS 物理参数，再恢复触屏九轴跟随。
4. 识别并接入模型自带 `daiji.motion3.json` 与适合桌宠的随机动作。
5. 重新接入 DeepSeek 对话、情绪标签、TTS 口型和摸头反应。
