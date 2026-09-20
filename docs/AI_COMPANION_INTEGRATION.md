# Sen 接入 AI 伴侣

Sen 不需要作为第二个 APK、后台服务或网络 SDK 运行。最终接入方式是把本仓库的原生
Live2D 源码和依赖直接并入 AI 伴侣 Android 工程，在同一个进程、同一个 APK 中用
`SenCompanionView` 替换现有静态立绘层。

模型资产继续由用户在本机导入 ZIP，解压到 App 私有目录；购买模型、贴图、动作和完整
VTube Studio 配置不得进入公开仓库或公共 APK。

## 稳定宿主入口

`SenCompanionView` 是透明 `GLSurfaceView`，同时实现 `SenCompanionController`：

```java
SenCompanionView senView = new SenCompanionView(context);
senView.setListener(listener);
senView.loadModel(model3File, autoIdleEnabled, SenOutfitCatalog.MAID);

senView.setEmotion("happy");
senView.playAction("nod");
senView.setSpeechAmplitude(ttsEnvelope); // 0..1
senView.setLookTarget(true, lookX, lookY); // -1..1
senView.setAutoIdle(true);
senView.setVisible(true);
```

情绪和动作的稳定字符串见 `SenPerformanceCatalog`；服装字符串见 `SenOutfitCatalog`。
AI 人格、对话语义到动作的选择逻辑仍由 AI 伴侣负责，Sen 层只执行已经实机确认的表现。

渲染回调来自 GL 线程。Android View 宿主可用 `Activity.runOnUiThread()`，Flutter 插件可用
主线程 Handler，把状态文字或错误转回 UI 线程。

## 生命周期

宿主必须对称调用：

```java
@Override public void onResume() {
    super.onResume();
    senView.onHostResume();
}

@Override public void onPause() {
    senView.onHostPause();
    super.onPause();
}

@Override public void onDestroy() {
    senView.release();
    super.onDestroy();
}
```

Flutter `PlatformView.dispose()`也必须调用`release()`。释放后的 View 不可重新加载；需要时
创建新实例。当前 Cubism Framework 是进程级单例，首轮集成只保持一个活动 Sen View。

## Flutter 分层

目标层级保持：背景 → `SenCompanionView` → 透明聊天框。先做一个最小 PlatformView 合成
验证，确认目标手机上的透明 Surface、上层 Flutter 透明区域和触摸路由正常，再搬测试页
中的模型导入与交互逻辑。若目标 Flutter 渲染模式不能稳定把 SurfaceView 放在两层之间，
再把相同 Renderer 接到 Flutter Texture/SurfaceTexture；不提前重写已验证的 Cubism 核心。

摸头框使用模型局部归一化坐标，不能保存屏幕像素。视线入口接受 OpenGL 舞台坐标
`-1..1`；TTS 只需持续输入归一化音量包络，不要求第一阶段就做音素级对口型。

## 迁移文件边界

直接迁移这些代码和资源：

- `SenCompanionView`、`SenCompanionController`、两个 Catalog；
- `SenRenderer`、`SenLive2DModel`、`SenPerformanceEngine`；
- `SenRenderOptions`、服装/外观/VTS 参数解析、纹理与文件加载辅助类；
- `sen-default-profile-v1.json`；
- 官方 Cubism Java Framework、Core AAR和`cubism-java-no-mipmap.patch`等构建配置。

保留在测试壳、不必进入最终聊天页面的内容：

- `MainActivity`整页测试 UI；
- 系统 TTS 的四句演示按钮；
- 手动 ZIP 表情/动作网格；
- 模型位置、摸头范围校准 UI（可留在开发者设置页）；
- Windows VTS 参数采集工具。

`MainActivity`仍保留完整测试入口，目的是每次迁移或底层升级后快速回归视觉，不是最终
AI 伴侣页面架构。

## 接入验收

1. 透明层在背景之上、聊天框之下，黑色只来自测试壳而不是 GL 清屏。
2. 导入同一 ZIP 后四套服装、21个情绪、程序动作、ZIP开关和原生键盘 motion不变。
3. v0.5.20连续双次弹跳、固化呆毛、兔耳双脉冲、尾巴镜像和摸头持续半闭眼不回退。
4. TTS 音量为0时嘴部音频层归零，播放时沿用已确认的90%幅度。
5. 页面反复进入/退出后没有重复 Cubism 初始化错误，也没有上一实例的纹理和大模型常驻。

