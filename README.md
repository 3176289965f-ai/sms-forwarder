# 短信转发 App v3.0

两台手机直连：**发送端**（收短信的备用机）→ 你自己的 EC2 中转 →
**接收端**（主力机）弹通知栏通知。不再依赖 Telegram / 微信。

适合备用机收验证码、银行通知等场景：主力机不用插两张卡，也能实时看到短信。

- 可选 Hachimi AI 预处理：对每条短信做分类、一句话摘要，并过滤垃圾短信。
- 发送失败本地重试：断网期间的短信进 outbox，网络恢复后自动补发。
- 所有 Token / Key 只存放在本机应用私有目录，不会上传到任何地方。
- 零第三方依赖：只用 Android 系统自带 API + Kotlin 标准库，包体小、无多余权限。

## 工作流程

```
备用机收到短信 → 关键词过滤 → [可选] Hachimi AI 分类/摘要
  → POST 到 EC2 中转（relay_server.py）
  → 主力机每 10 秒轮询 → 通知栏通知 + App 内收件箱
```

Telegram 通道保留为备选；微信 ClawBot 通道已折叠隐藏（灰度保留，配置还在）。

## 使用步骤

### 步骤 0：在 EC2 上起中转服务（只需做一次）

按 [`relay-server/EC2_SETUP.md`](relay-server/EC2_SETUP.md) 操作，
全程约 5 分钟：上传 `relay_server.py` → 生成 Token → systemd 常驻 →
Caddy 加 `/sms-relay/*` 反代 → 公网 curl 验证。

记下两样东西：**中转地址**（如 `https://xsmlink.duckdns.org/sms-relay`）
和 **Token**（`relay_token.txt` 里的那串），两台手机填同一个。

### 步骤 1：两台手机都安装

去 [Releases](../../releases) 页下载最新版 `sms-forwarder.apk`（约 1.5MB）。
已装过 v1/v2 的用户直接覆盖安装即可，设置保留（微信目标会自动切到直连中转）。

### 步骤 2A：备用机设为发送端

1. 打开 App，选 **📲 发送端**。
2. 允许**短信权限**（必须，否则收不到短信）。
3. 转发目标选 **直连中转（经你的 EC2，默认）**。
4. 填写**中转地址**、**中转 Token**、本机名称，点「保存设置」，
   再点「测试直连中转」——成功后去主力机确认能收到测试消息。
5. 保活：设置 → 电池 → 短信转发 → **无限制**；允许自启动；锁定后台。

### 步骤 2B：主力机设为接收端

1. 打开 App，选 **🔔 接收端**。
2. 允许**通知权限**（Android 13+ 会弹窗申请）。
3. 填写同样的**中转地址**和 **Token**，点「保存设置」，
   再点「测试中转连通性」。
4. 点「启动接收服务」——通知栏出现常驻的「短信转发 · 接收中」即正常。
5. 保活同上。重启手机后服务会自动恢复。

### 步骤 3：实测

用另一部手机给备用机发一条短信，10 秒内主力机应弹出通知。
点通知可进 App 查看收件箱。

## Telegram 备选通道（发送端）

转发目标切到 Telegram，按 v2 的老方式配置 Bot Token / Chat ID 即可
（大陆网络下发送端需境外网络）。

## 关于 Hachimi AI 预处理

- 默认**关闭**，按需开启。开启后每条短信会先发往 `ai.hachimi.online` 做分类，
  增加约 1~3 秒延迟和少量流量。
- 短信常含验证码、银行通知等敏感信息，是否经第三方 AI 处理请自行权衡。
- App 内置了连通性自检（两个答案不同的问题，对比回答是否一致），
  如果提示"疑似固定回复"，说明该线路当前不可用于真实 AI 任务，建议保持关闭。

## 自行编译

`./build-v3.sh`（需要 `~/android-dev` 工具链：JDK 17 + build-tools 34 + Kotlin 2.0），
或用 Android Studio 打开项目根目录 Build > Build APK（JDK 17 + Android SDK platform 34）。

包名 `com.example.smsforward`，最低支持 Android 8.0（API 26），零第三方依赖。

## 版本升级说明（给维护者）

1. 改代码后把 `app/build.gradle.kts` 里的 `versionCode` 加 1（`versionName` 同步改），
   `build-v3.sh` 里的 `VCODE`/`VNAME` 同步改。
2. **必须用同一个签名密钥打包**，否则用户无法覆盖安装：
   `workspace/sms-forwarder-v2/debug.keystore`（storepass/keypass 均为 `android`）。
3. 在 GitHub 发新 Release（tag 如 `v3.0`），把新 apk 作为附件上传，
   并在 [CHANGELOG.md](CHANGELOG.md) 里记录更新历史。

## 许可证

本项目采用 MIT 许可证，见 [LICENSE](LICENSE)。
