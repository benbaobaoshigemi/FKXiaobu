# FK小布：本地入口与 ChatGPT 桥接

针对小布 Next `com.oplus.claw` 的 LSPosed 模块，当前适配 `17.0.75_beta_1882dc7_261008`（versionCode `170075`）。模块本身通过本机应用提供 UI、官方 ChatGPT 登录和 loopback API 服务。

## 实机验证状态

- 本地入口：已在手机上进入小布 Next 主界面。
- 官方 ChatGPT 登录：已完成，OAuth 回调、身份签名和方案权限范围均通过验证；凭据只保存在 FK小布 的 Android Keystore 加密存储中。
- 模型目录：手机请求成功，返回账户模型目录；`gpt-6-luna` 优先作为默认模型，其他账户目录模型也可用于本地自定义 Responses provider。
- 推理：手机通过本机桥向官方 Responses API 完成了一次真实 GPT-6 Luna 推理，并收到 `response.completed`。首次探测的 HTTP 400 原因是请求把 `input` 发成字符串；改为消息列表后通过。
- 提供方接入：模块通过小布自带 `settings.models.custom.upsertAndSelect` 配置路径写入账户目录中的首选模型；当前账户有 Luna 时默认选 `custom/gpt-6-luna`。桥接密钥读取仅允许小布 Next UID。

## 接入步骤

1. 安装 `dist/FKXiaobu-0.3.0.apk`。
2. 在 LSPosed 启用“FK小布 · 本地入口”，作用域只选小布 Next，然后重启小布 Next。
3. 打开 FK小布，启动手机本机服务，并通过官方 ChatGPT 页面授权。
4. 模块从官方账户模型目录同步首选模型到小布自定义提供方；目录包含 `gpt-6-luna` 时优先选用 Luna，否则选择目录中的第一个模型。小布自定义 provider 可配置目录中的其他模型 ID。
5. 打开小布 Next，发一条短消息即可开始使用。

本机桥只监听 `127.0.0.1`，只转发到官方 `https://api.openai.com/v1/responses`。桥接可接受账户模型目录返回的模型 ID；OpenAI 仍会对每个实际推理请求执行账户授权校验。没有目录访问权或推理权的模型不会因此获得云端权限。

## 回退

在 LSPosed 关闭模块并重启小布 Next；在 FK小布 中停止服务或退出登录。模块不会改写小布的服务端资格结果。

## 构建

需要 JDK 17 与 Android SDK 36。运行：

```sh
./gradlew :app:assembleDebug
```
