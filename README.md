# 听会

开会录音转文字的 App：录完自动转写、分出谁说的、出纪要和待办，能接着问「小灯」，也能一键发到飞书。

## 下载

**[点这里下载 听会 v2.11（安卓，68 MB）](https://github.com/wailii/tinghui/releases/latest/download/tinghui-v2.11.apk)**

**怎么装**：用手机浏览器点上面的链接，下载完点开安装包。手机提示「未知来源」或「外部来源应用」时选允许（vivo 要先勾「已了解风险」再点继续安装）。

- 需要安卓 12 及以上
- 转写用阿里云百炼，要自己的 Key（[百炼控制台](https://bailian.console.aliyun.com/) 申请，按量计费，40 分钟的会约几分钱），在 App 设置里填
- 出纪要、问小灯要一个大模型接口（OpenAI 兼容的地址 + Key + 模型名，比如 DeepSeek），也在设置里填
- 飞书是可选的：填自己建的飞书应用后能发纪要、建任务

## 源码

- `android/`：安卓工程（Kotlin + Compose），用 Android Studio 打开
- 自己打包：`./gradlew assembleShareRelease`
- 声纹认人用的是 sherpa-onnx 和 CAM++ 模型（在 `app/libs`、`app/src/main/assets`）

MIT 协议，见 LICENSE。
