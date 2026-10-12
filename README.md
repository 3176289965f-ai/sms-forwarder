# 短信转发 App

把一台安卓手机收到的短信，自动转发到 Telegram 机器人或微信（ClawBot）。
适合备用机收验证码、银行通知等场景：主力机不用插两张卡，也能实时看到短信。

可选开启 Hachimi AI 预处理：对每条短信做分类、一句话摘要，并过滤垃圾短信。

## 工作流程

```
短信到达 → 关键词过滤 → [可选] Hachimi AI 分类/摘要 → Telegram Bot API 和/或 OpenClaw 网关 → Telegram / 微信
```

- AI 步骤任何失败（超时、无网络、返回异常）都会**自动降级为原文转发**，不影响主流程。
- 所有 Token / Key 只存放在本机应用私有目录，不会上传到任何地方。
- 零第三方依赖：只用 Android 系统自带 API + Kotlin 标准库，包体小、无多余权限。

## 使用步骤

### 步骤 1：下载安装

去 [Releases](../../releases) 页下载最新版 `sms-forwarder.apk`（约 1.5MB），
传到**收短信的那台手机**上安装（安装时允许"安装未知应用"）。
已装过 v1 的用户直接覆盖安装即可，设置保留。

### 步骤 2：选择转发目标

打开 App，首次会申请**短信权限**，必须允许，否则收不到短信。

在「转发目标」里三选一：

- **Telegram（默认）**：只发到 Telegram 机器人；
- **微信 ClawBot（经由 OpenClaw 网关）**：只发到微信，大陆网络可用；
- **两者都发**：两边都发。

### 步骤 3A：配置 Telegram（选了 Telegram 才需要）

1. 在 Telegram 里找到 **@BotFather**，发送 `/newbot`，按提示起个名字，
   拿到 **Bot Token**（形如 `123456:ABC-DEF…`，妥善保存）。
2. 给你刚创建的机器人发任意一条消息（比如 "hi"）。
3. 在浏览器打开 `https://api.telegram.org/bot<你的Token>/getUpdates`，
   在返回的 JSON 里找到 `"chat":{"id":123456789}`，这个数字就是 **Chat ID**。
   （偷懒办法：直接找 @userinfobot，它会告诉你自己的 ID。）
4. 回到 App 填写 **Bot Token** 和 **Chat ID**，点"保存设置"，
   再点"测试 Telegram 转发"，去 Telegram 确认收到测试消息。

### 步骤 3B：配置微信 ClawBot（选了微信才需要）

先在电脑/服务器上装好 OpenClaw 网关并登录微信：

```
① npx -y @tencent-weixin/openclaw-weixin-cli@latest install
② openclaw channels login --channel openclaw-weixin（微信扫码）
③ 用 openclaw message channel list 查目标 ID
④ 保证手机能访问网关地址（同一 Wi-Fi 填内网 IP，或用中转地址）
```

然后在 App 的「微信 ClawBot 设置」里填写：

- **OpenClaw 网关地址**（如 `http://192.168.1.10:18789` 或你的中转地址）
- **网关 API Token**（OpenClaw 配置里的 gateway token）
- **微信目标 ID**（上一步查到的目标 ID）
- **发送路径**（高级，一般不用改，默认 `/rpc`）

点"测试连接"，看到成功提示后再点"保存设置"。

### 步骤 4：保活设置（重要）

国产 ROM 容易杀后台，请在收短信的手机上：

- 设置 → 电池 → 找到"短信转发" → 设为**无限制 / 允许后台活动**；
- 允许**自启动**；
- 把 App 锁定在多任务后台（上滑锁定）。

### 网络要求

- **Telegram 通道**：发信端（收短信的那台手机）调用 `api.telegram.org` 需要能出境，
  大陆直连会被墙；收信端需要能正常用 Telegram。
- **微信通道**：发信端只需要能访问你填的 OpenClaw 网关地址；
  微信收信在大陆网络正常。

## 关于 Hachimi AI 预处理

- 默认**关闭**，按需开启。开启后每条短信会先发往 `ai.hachimi.online` 做分类，
  增加约 1~3 秒延迟和少量流量。
- 短信常含验证码、银行通知等敏感信息，是否经第三方 AI 处理请自行权衡。
- App 内置了连通性自检（两个答案不同的问题，对比回答是否一致），
  如果提示"疑似固定回复"，说明该线路当前不可用于真实 AI 任务，建议保持关闭。

## 自行编译

直接用本仓库源码：Android Studio 打开项目根目录，
Build > Build APK 即可（需要 JDK 17 + Android SDK，platform 34）。

包名 `com.example.smsforward`，最低支持 Android 8.0（API 26），零第三方依赖。

## 版本升级说明（给维护者）

1. 改代码后把 `app/build.gradle.kts` 里的 `versionCode` 加 1（`versionName` 同步改）。
2. **必须用同一个签名密钥打包**，否则用户无法覆盖安装。
   首次正式打包时生成自己的 keystore 并妥善保管，丢失后无法再发布可升级的版本。
3. 在 GitHub 发新 Release（tag 如 `v2.0`），把新 apk 作为附件上传，
   并在 [CHANGELOG.md](CHANGELOG.md) 里记录更新历史。

## 许可证

本项目采用 MIT 许可证，见 [LICENSE](LICENSE)。
