# Sen Live2D Android 任务总账

> **这是本项目唯一的任务与交接事实源。** 新对话、新 AI 或新开发者必须先完整阅读本文件，再检查仓库和构建记录。不要只根据聊天摘要继续修改。
>
> 维护规则：每轮动手前先写入“本轮制作、待实机确认”；用户确认后，再移入“实机已确认”。失败结果也必须记录，不能删除或反复覆盖打补丁。任何新发现都要同步更新“踩坑点”和“下一步顺序”。

## 状态说明

- `已制作待确认`：代码和构建已完成，但用户尚未在目标手机/电脑确认效果。
- `实机已确认`：用户已经明确反馈结果。
- `已定位待修复`：原因已有直接证据，但修复包尚未实机确认。
- `暂缓`：有意推迟，不应被下一位 AI 擅自提前。
- `废弃路线`：已经证明不适用；禁止再次从头尝试。

## 当前目标

先让 Sen 模型在 Android 静止显示时与 VTube Studio 一致。当前拆成两个互不混淆的关卡：

1. **渲染关卡**：解决贴图黑影，让26张2K贴图正确显示颜色、透明度、蒙版和绘制顺序。
2. **状态关卡**：复现 VTube Studio 的正常待机参数，解决头发、眼球、眼眶、眼皮和可选外观部件同时重叠的问题。

两个关卡全部确认以前，不加入九轴自主动作、LLM 情绪、TTS或摸头反应。

## 当前版本与下一步顺序

- Android：`v0.2.1-native-texture-fix`，**已制作待确认**。
- Windows：`Sen VTS Parameter Capture v0.1.0`，**已制作待电脑确认**。
- 固定顺序：先确认 v0.2.1 是否从黑影恢复为有颜色贴图；再采集 VTS“正常待机”参数包；然后接入 Android 参数包导入；最后才处理九轴、物理和动作。

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

### v0.2.1-native-texture-fix（本轮）

- [ ] 26张2K贴图不生成 mipmap，官方 Java Framework 的每帧过滤同步改为 `GL_LINEAR`，人物不再是纯黑剪影。
- [ ] 贴图颜色、透明区域和剪贴蒙版能正常显示。
- [ ] 状态栏明确显示 `GL_LINEAR`，可与旧 v0.2.0 黑影包区分。
- [ ] 修复贴图以后，记录所有仍然异常的头发、眼睛、外观和部件；这些属于下一关的 VTS 初始参数复现，不与黑影修复混为一谈。

### Sen VTS Parameter Capture v0.1.0（电脑端，本轮）

- [ ] Windows EXE 能连接电脑端 VTube Studio 的官方 WebSocket API；首次连接能正常弹出授权。
- [ ] VTS 载入 Sen 后，能读取当前模型、Live2D 参数、输入参数、活动表情、热键和物理设置。
- [ ] “正常待机”和手动开启的表情/动作能分别命名采集；每次连续读取9帧并取中位数。
- [ ] 能保存 `Sen.vts-profile.json`，且文件不包含贴图、moc3或授权令牌。
- [ ] 当前版本只负责采集，不直接修改 VTS 或 Android 模型；Android 导入在静态贴图确认后制作。

### v0.2.0-native-renderer-test

- [x] 使用官方 `CubismJavaFramework` 5-r.5 与 `Live2DCubismCore.aar`，不经过 WebView/JavaScript/WebAssembly，能在实机完成139.2 MiB moc3的 Core Model 创建。
- [x] 原始26张2K贴图逐张解码上传，不生成 mipmap；加载进度能明确显示到第几张贴图。
- [ ] Sen 静态画面与 VTube Studio 对照一致：无白色头发大块、眼球/眼眶/眼皮不错误重叠、剪贴蒙版与绘制顺序正确。
- [ ] 自动应用 `.vtube.json` 的 `Watermark` 启动表情后水印消失；“查看原始状态”仍可对照水印原始状态。
- [x] 上方约2/3为原生模型预览，下方约1/3保留 ZIP 表情测试按钮。
- [x] App 无需联网或单独导入 Web Core；APK 内的 Java Core AAR可在用户 arm64-v8a 设备正常加载。
- [x] v0.2.0 固定使用仓库内公开 debug keystore；从本版开始后续测试包可直接覆盖安装。v0.1.x 临时签名与本版不同时，首次迁移需卸载旧版并重新导入 ZIP。

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

- v0.2.0 原生版能完成139.2 MiB moc3创建和26张2K贴图上传，能绘制完整人物几何轮廓；因此原生 Core、模型矩阵和主要 Drawable 绘制链路已经运行。
- v0.2.0 最终人物为纯黑剪影。直接代码对照确认：App只上传贴图 level 0、不生成 mipmap，但官方 `CubismShaderAndroid.setUpTexture()` 每帧又将 `GL_TEXTURE_MIN_FILTER` 强制设为 `GL_LINEAR_MIPMAP_LINEAR`。没有完整 mipmap 的贴图会被 OpenGL 当作不完整贴图并采样为黑色。该原因已进入 v0.2.1 修复，待实机确认。
- 黑影轮廓仍能看到头顶异常大块，因此头发问题并未因改用原生渲染自动消失；眼球、眼眶、眼皮和其他可选部件也必须视为“可能同时异常”，不能只修头发外观。
- v0.1.4 导入 Sen 2K ZIP 后，上半区保持黑色，进度一直停在“moc3 已读取，正在创建 Cubism 5 模型…”。这说明程序尚未到达表情、物理、贴图解码、贴图上传或首帧采样阶段；1K兼容贴图不会触发，也不能解决当前故障。
- 此前的 `null.release()` 与 `null.isHit()` 均已不再出现，但这只代表官方示例空对象防护生效，不代表模型已经渲染成功。

## 踩坑点（后续 AI 必读）

### 不生成 mipmap 时必须同步修改官方 Java Shader 的过滤方式

- App 的 `NativeTextureManager` 将26张2K贴图逐张上传，只定义 level 0，并设置 `GL_LINEAR`。但官方 Java Framework 5 R5 会在 `CubismShaderAndroid.setUpTexture()` 的每次绘制中重新设置 `GL_LINEAR_MIPMAP_LINEAR`，覆盖加载阶段的设置。
- “加载全部成功、几何轮廓正确、整个人物纯黑”正是贴图不完整的典型结果，不应再怀疑 moc3、降低分辨率或拆解 VTube Studio APK。
- Sen 使用26张2K贴图，生成完整 mipmap 约增加三分之一贴图显存。当前项目通过仓库补丁 `patches/cubism-java-no-mipmap.patch` 把过滤改为 `GL_LINEAR` 与 `GL_CLAMP_TO_EDGE`，GitHub Actions 必须在 Gradle 构建前应用该补丁。
- `Framework` 是官方 submodule，不能把未推送的本地 submodule commit 当作修复提交；否则 GitHub Actions 无法检出。补丁必须保存在主仓库并由工作流显式应用。

### VTube Studio 是校准基准，不是需要反编译的运行依赖

- Sen 包内 `.vtube.json` 已含62组 `ParameterSettings`、51个热键、物理/平滑设置、模型位置和 `SavedActiveExpressions`。目前 Android 只应用了 Watermark 表情，没有执行完整输入→输出映射，因此原始默认状态不等于 VTS 正常待机状态。
- 不反编译、不复制、不重新分发 VTube Studio。优先使用其官方 Public API 读取当前全部 Live2D 参数、表情、热键和物理设置。
- 电脑采集器位于 `tools/vts-profile-capture/`。第一版输出 `Sen.vts-profile.json`；后续 Android 只导入参数包，不依赖 VTube Studio 持续运行。

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

## 后续任务队列

1. 实机验证 v0.2.1 是否恢复2K贴图颜色；把结果更新为“实机已确认”。
2. 电脑端采集“正常待机”参数；若工具报错，保存错误原文并修复采集器。
3. Android 增加 `Sen.vts-profile.json` 导入、参数ID校验、缺失参数报告和一键恢复正常待机。
4. 对照 VTS 修正头发、眼睛、服装和可选外观互斥状态；静态画面通过后锁定基线。
5. 解析 `.vtube.json` 的62个 ParameterSettings，移植输入范围、输出范围和平滑；再对比移植物理参数和触屏九轴。
6. 识别并接入模型自带 `daiji.motion3.json` 与适合桌宠的随机动作。
7. 最后重新接入 DeepSeek 对话、情绪标签、TTS 口型和摸头反应。

## 新对话/新 AI 接手清单

1. 先完整阅读本总账，再读 `README.md` 和最近提交；不要只看聊天记录。
2. 检查用户最新实机反馈属于“渲染关卡”还是“状态关卡”，禁止混修。
3. 动手前把本轮计划登记到“待实机确认”；完成构建后记录版本、commit和Actions run。
4. 用户说“效果不错/没有问题”后才把对应条目改成“实机已确认”；部分成功必须拆开记录。
5. 不提交用户购买的 Sen ZIP、moc3、贴图、表情或 VTS 配置；电脑采集出的用户参数包也不提交公开仓库。
6. 任何新错误都加入对应踩坑点，尤其记录最后可见进度、版本号和是否已进入贴图绘制阶段。
