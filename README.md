# 短信转发 App

把这台安卓手机收到的短信，自动转发到你 Telegram 上的机器人聊天里。
可选开启 Hachimi AI 预处理：对每条短信做分类、一句话摘要，并过滤垃圾短信。

## 工作流程

```
短信到达 → 关键词过滤 → [可选] Hachimi AI 分类/摘要 → Telegram Bot API → 你的 Telegram
```

- AI 步骤任何失败（超时、无网络、返回异常）都会**自动降级为原文转发**，不影响主流程。
- 所有 Token / Key 只存放在本机应用私有目录，不会上传到任何地方。
- 零第三方依赖：只用 Android 系统自带 API + Kotlin 标准库，包体小、无多余权限。

## 1. 创建 Telegram 机器人

1. 在 Telegram 里找到 **@BotFather**，发送 `/newbot`，按提示起名字，拿到 **Bot Token**（形如 `123456:ABC-DEF…`）。
2. 给你刚创建的机器人发任意一条消息（比如 "hi"）。
3. 在浏览器打开 `https://api.telegram.org/bot<你的Token>/getUpdates`，
   在返回的 JSON 里找到 `"chat":{"id":123456789}`，这个数字就是 **Chat ID**。
   （也可以直接找 @userinfobot 查自己的 ID。）

> 注意：Telegram 在国内直连通常不通。**收信端**（你这台手机）需要能正常用 Telegram；
> **发信端**（另一台手机）调用 `api.telegram.org` 也需要能出境，否则转发会失败。
> 如果发信端在国内，可以给它开代理/换用境外卡，或把转发目标换成企业微信机器人。

## 2. 安装

1. 把 `app-debug.apk` 传到另一台安卓手机上安装
   （安装时允许"安装未知应用"）。
2. 打开 App，首次会申请**短信权限**，必须允许，否则收不到短信。
3. 填写 **Bot Token** 和 **Chat ID**，点"保存设置"。
4. 点"测试 Telegram 转发"，去 Telegram 里确认收到测试消息。
5. （可选）填写 **Hachimi API Key**，打开 AI 开关，先点"测试 Hachimi 连通性"
   确认线路正常再启用。

## 3. 保活设置（重要）

国产 ROM 容易杀后台，请在另一台手机上：

- 设置 → 电池 → 找到"短信转发" → 设为**无限制 / 允许后台活动**；
- 允许**自启动**；
- 把 App 锁定在多任务后台（上滑锁定）。

## 4. 关于 Hachimi AI 预处理

- 默认**关闭**，按需开启。开启后每条短信会先发往 `ai.hachimi.online` 做分类，
  增加约 1~3 秒延迟和少量流量。
- 短信常含验证码、银行通知等敏感信息，是否经第三方 AI 处理请自行权衡。
- App 内置了连通性自检（两个答案不同的问题，对比回答是否一致），
  如果提示"疑似固定回复"，说明该线路当前不可用于真实 AI 任务，建议保持关闭。

## 5. 自行编译（开发者）

源码在 `sms-forwarder-src.zip`。用 Android Studio 打开项目根目录，
点 Build > Build APK 即可（需要 JDK 17 + Android SDK，platform 34）。

包名 `com.example.smsforward`，最低支持 Android 8.0（API 26），零第三方依赖。
