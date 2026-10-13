# 短信转发 v3.0：EC2 中转服务上机指南

在 EC2（Instance Connect）上执行，全程约 5 分钟。
中转地址：`https://xsmlink.duckdns.org/sms-relay`（走已有 Caddy 的 443 端口，无需开安全组）。

## 1. 上传 relay_server.py

把 `relay-server/relay_server.py` 的内容传到 EC2（任选一种）：

- **A. 直接粘贴**：`mkdir -p ~/sms-relay && cd ~/sms-relay`，然后
  `cat > relay_server.py` 回车，把文件内容整段粘贴进去，末行单独输入 `EOF` 回车。
  （注意：手机屏幕翻译可能把英文显示成中文，粘贴后用 `head -5 relay_server.py`
  确认是英文；保险起见用 B。）
- **B. 传文件**：Instance Connect 的文件传输功能直接上传。

验证：`python3 -c "import py_compile; py_compile.compile('relay_server.py', doraise=True)" && echo OK`

## 2. 生成 Token（两台手机 App 里要填同一个）

```bash
cd ~/sms-relay
openssl rand -hex 16 > relay_token.txt
cat relay_token.txt   # 记下来，等下填进两台手机的 App
```

## 3. systemd 常驻

```bash
sudo tee /etc/systemd/system/sms-relay.service > /dev/null <<'EOF'
[Unit]
Description=SMS forwarder v3.0 relay
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/home/ubuntu/sms-relay
ExecStart=/usr/bin/python3 /home/ubuntu/sms-relay/relay_server.py --port 8765
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable --now sms-relay
sleep 2
curl -s http://127.0.0.1:8765/sms-relay/health   # 应看到 {"ok": true, ...}
```

> 如果用户名不是 `ubuntu`，把上面两处 `ubuntu` 换成实际用户名（`whoami` 查看）。

## 4. Caddy 加路由

找到 Caddyfile 里 `xsmlink.duckdns.org` 的 site 块，在里面加一段：

```
handle /sms-relay/* {
    reverse_proxy 127.0.0.1:8765
}
```

然后：

```bash
caddy validate --config /etc/caddy/Caddyfile   # 路径按你实际的来
sudo systemctl reload caddy
```

## 5. 公网验证（EC2 本机执行）

```bash
TOKEN=$(cat ~/sms-relay/relay_token.txt)
# 发一条
curl -s -X POST https://xsmlink.duckdns.org/sms-relay/send \
  -H "X-Relay-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"sender":"测试","body":"hello 1234","received_at":1,"device":"ec2"}'
echo
# 取回
curl -s "https://xsmlink.duckdns.org/sms-relay/poll?after=0" -H "X-Relay-Token: $TOKEN"
echo
```

两条都通，说明中转已上线。之后两台手机的 App 里：
中转地址填 `https://xsmlink.duckdns.org/sms-relay`，Token 填第 2 步生成的串。

## 6. 日常维护

- 看日志：`journalctl -u sms-relay -f`
- 重启：`sudo systemctl restart sms-relay`
- 消息存在 `~/sms-relay/relay_store.json`（最多 1000 条，重启不丢）。
- 之前给 Cloudflare Worker 做的 `/4f46194b-...` 反代如果还在 Caddyfile 里，
  可以删掉（Worker 已删，那条已失效），不删也不影响。
