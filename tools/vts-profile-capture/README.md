# Sen VTube Studio 参数采集器

这是 Sen 专用电脑端校准工具的第一版。它通过 VTube Studio 官方公开 WebSocket API 读取当前模型状态，不读取、修改或反编译 VTube Studio 本体。

## 使用方法

1. 在 Windows 电脑启动 VTube Studio，并载入 Sen 模型。
2. 启动 `Sen-VTS-Parameter-Capture.exe`，点“连接并授权”。
3. 第一次连接时，在 VTube Studio 弹窗中允许插件访问。
4. 保持角色为正常状态，采集“正常待机”。
5. 在 VTube Studio 开启一个表情或动作，填写对应名称后再次采集。
6. 点“保存参数包”，得到 `Sen.vts-profile.json`，用于 Android 端对照和移植。

工具连续读取9帧 Live2D 参数并取中位数，减少眨眼、呼吸和物理摆动造成的偶然误差。授权令牌只保存在当前电脑 `%APPDATA%/SenVTSParameterCapture/config.json`，不会写入仓库或参数包。

## 当前边界

- v0.1.0 负责采集，不直接修改模型参数。
- Android 导入和自动复现将在静态贴图显示正确后接入。
- 输出中保留当前模型、全部 Live2D 参数、输入参数、活动表情、热键和物理设置；不包含模型贴图或 `.moc3`。
