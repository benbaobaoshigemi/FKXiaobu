# 静态分析与验证记录

日期：2026-10-23。

目标包为 `com.oplus.claw`，版本 `17.0.75_beta_1882dc7_261008`，versionCode `170075`。
ADB 前台 Activity 为 `com.oplus.claw/.MainActivity`。
原始 UI 提示“咦，内测资格验证未通过”“当前账号的设备无法通过验证，请重试或联系工作人员”。

从用户手机提取安装 APK，仅在本地分析。APK 和反编译文件位于被 gitignore 排除的 `analysis/`。
JADX 对整个 APK 报告 892 项反编译错误，因此不能声称已审阅整个应用。
本次使用的路由及状态构造器已成功反编译。

## 相关位置（反编译文件）

- `com/oplus/claw/welcome/z3.java` 的 `n`：根据状态选择隐私、资格失败、首次引导或主页面。
- `com/oplus/claw/welcome/r.java`：AccessLayerState。构造器第 10 个参数是 gateStage，第 12 个参数是 displayFailed，第 14 个参数控制启动 splash 延迟释放。
- `com/oplus/claw/welcome/j.java`：Unqualified 显示路由。
- `com/oplus/claw/welcome/g.java`：Main 显示路由。JADX 将单例字段显示为 `f53291a`，运行时按静态字段类型定位，避免使用反编译器重命名。
- `com/oplus/claw/welcome/c2.java`：BackgroundModelAccessPreflight，保留原实现。
- `com/oplus/claw/welcome/w2.java`：模型访问就绪状态，保留原实现。
- `com/oplus/claw/settings/e4.java`：自定义提供商模板，包含 `custom`、`自定义` 与 `openai-completions`。
- `com/oplus/claw/settings/f0.java`：`openai-codex` 配置分支，包含旧 `backend-api` 地址；不将它视为官方登录接入方案。

## 补丁范围

模块只挂钩 Application.attach 用于核验目标版本，以及 AccessLayerState 的一个构造器。
当 gateStage 是 Unqualified 时，仅替换显示路由、清除显示失败面板、释放启动 splash。
不修改构造器的第一个参数（实际授权 UI 状态），也不修改模型访问、资格缓存、
账号、设备标识、令牌、网络请求、重试或任何响应。

后续的本地模型接入挂钩仅用于：把手机 127.0.0.1 回环地址加入提供方 URL 检查；
在官方登录完成且官方模型目录含 GPT-6 Luna 后，通过小布原生 `settings.models.custom.upsertAndSelect`
配置通道保存唯一的 `custom/gpt-6-luna` 本机提供方，并选作默认模型。它不改内置模型、
云端资格、账号、设备标识或 OAuth token。私有模块服务的 ContentProvider 只允许小布 Next UID
读取随机本机桥接密钥。

## 已完成验证

- Gradle debug APK 构建成功。
- apksigner 验证 APK 签名成功（v2）。
- aapt 确认包名 `io.github.fkxiaobu`、版本 `0.1.1` 和启动 Activity。
- ADB 安装返回 `Success`。

## 实机验证

- LSPosed 启用并选定目标作用域。
- 重新启动目标应用后出现模块 ready / route opened 日志。
- 本地主界面可见。
- 小布本地配置文件已核实 `openai-responses`、`http://127.0.0.1:19081/v1`、`gpt-6-luna` 与默认模型 `custom/gpt-6-luna`。
- 手机经本机桥接向官方 Responses API 完成一次 GPT-6 Luna 推理，收到 `response.completed`。
- 模块 ContentProvider 对非小布 UID 返回 `SecurityException`；本机未认证请求及错误密钥请求均返回 401。

这里的推理成功只证明当前授权账户的 GPT-6 Luna 单次调用可用，不据此推断其他模型或未来授权状态。

## 注入实测（0.1.1）

LSPosed 日志确认同一个目标应用进程中出现：

```text
11:24:13.602 FKXiaobu: presentation hook ready for 17.0.75_beta_1882dc7_261008
11:24:13.968 FKXiaobu: local UI route opened; server/model authorization unchanged
```

0.1.0 首次实测因使用 JADX 重命名字段导致 NoSuchFieldError，未生效；0.1.1 已改为按类型定位单例。
随后手机进入目标应用拉起的“融合搜索服务”安装界面，暂不能据此认定主页面可用。
截图为 `analysis/local-ui.png`。尚待依赖页面处理后验证目标应用界面。

## 本地界面验证成功

用户处理依赖页面后，重新将小布 Next 置于前台，实际 UI 树读取到：
“小布”“跟进项目”“最新动态”“切换聊天智能体：小布”“按住说话”“添加图片”。
通过前台包名检查后采集 `analysis/local-main.png` 并人工查看，确认显示小布 Next 首页，
而非资格失败页面。对应 XML 为 `analysis/local-main.xml`。
因此可确认当前版本的本地入口补丁已生效；不据此推断任何模型请求可用。

最终 0.1.1 APK 构建、签名验证、ADB 升级安装成功。Android Lint 完成，0 errors、5 warnings
（SDK/Gradle 版本提示、备份规则提示和界面文字资源提示），报告见
`app/build/reports/lint-results-debug.html`。
