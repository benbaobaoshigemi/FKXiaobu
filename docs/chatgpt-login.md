# ChatGPT 账户登录与模型调用

## 当前实机状态

- 使用官方 Sign in with ChatGPT 授权流程完成登录，校验 issuer、签名、state、nonce、账号主体和授权 scopes。
- 在手机上成功取得账户模型目录，目录包含 `gpt-6-luna`。
- 通过手机 loopback 桥向官方 `https://api.openai.com/v1/responses` 发送真实请求，收到 `response.completed`。
- 首次桥接探测曾因把 `input` 发送为字符串而收到 HTTP 400 `Input must be a list`。现已改为 Responses API 消息数组格式，并实测成功。
- LSPosed 模块随后调用小布自身的 `settings.models.custom.upsertAndSelect` 配置路径，写入 `openai-responses`、本机桥接地址和 `gpt-6-luna`，并设为默认模型。实机 `openclaw.json` 已核实该配置。

## 实现边界

OAuth access/refresh/id tokens 由 FK小布 Android Keystore 加密保存，不进入小布配置。小布配置只拿到随机生成的本机桥接密钥；导出的 ContentProvider 通过调用 UID 校验，只有 `com.oplus.claw` 可读取密钥。

桥接只监听 `127.0.0.1:19081`，只接受固定的 GPT-6 Luna，并只向 OpenAI 官方 HTTPS Responses API 转发。请求设置 `store: false`、`stream: true`；不重试权限拒绝，也不改用其他模型。

模型目录出现 Luna 和单次推理完成分别验证了目录可见性及实际调用成功；这不表示其他模型也可用。

参考：

- [OpenAI Sign in with ChatGPT](https://developers.openai.com/siwc/quickstart)
- [开放源代码应用的方案](https://developers.openai.com/siwc/token-sharing-open-source)
- [模型目录与调用](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
