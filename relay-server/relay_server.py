#!/usr/bin/env python3
"""
短信转发 v3.0 中转服务（EC2 自建 relay）。

只做一件事：收短信 JSON，排队；另一台手机来轮询取走。
零第三方依赖（Python 标准库），跑在 127.0.0.1:8765，前面用 Caddy 反代 + HTTPS。

接口（Token 放在请求头 X-Relay-Token 里，health 除外）：
  GET  /sms-relay/health            -> {"ok":true,"count":N,"latest":ID}（免鉴权，供探活）
  POST /sms-relay/send              -> {"sender","body","received_at","device","note"?}
                                       返回 {"ok":true,"id":ID}
  GET  /sms-relay/poll?after=ID      -> {"messages":[{...}],"latest":ID}

Token 来源（按优先级）：--token 参数 > SMS_RELAY_TOKEN 环境变量 >
                        relay_token.txt（与本脚本同目录，第一行）。
启动时 Token 为空则直接报错退出，避免无鉴权裸奔。

消息持久化到 relay_store.json（与脚本同目录），上限 1000 条，超了丢最旧的。
EC2 重启不丢近期的码。
"""
import argparse
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

BASE_PATH = "/sms-relay"
STORE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "relay_store.json")
MAX_MESSAGES = 1000
MAX_BODY_LEN = 5000
MAX_SENDER_LEN = 100


class Store:
    def __init__(self):
        self.lock = threading.Lock()
        self.messages = []  # [{id, sender, body, received_at, device, note, server_at}]
        self.next_id = 1
        self._load()

    def _load(self):
        try:
            with open(STORE_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            self.messages = data.get("messages", [])[-MAX_MESSAGES:]
            self.next_id = int(data.get("next_id", 1))
            if self.messages:
                self.next_id = max(self.next_id, max(m["id"] for m in self.messages) + 1)
        except (FileNotFoundError, ValueError, KeyError, TypeError):
            pass
        except Exception as e:
            print(f"[relay] store load failed ({e}), starting empty", flush=True)

    def _save(self):
        tmp = STORE_FILE + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump({"messages": self.messages, "next_id": self.next_id}, f,
                          ensure_ascii=False)
            os.replace(tmp, STORE_FILE)
        except Exception as e:
            print(f"[relay] store save failed: {e}", flush=True)

    def add(self, sender, body, received_at, device, note):
        with self.lock:
            sender = sender[:MAX_SENDER_LEN]
            device = (device or "")[:100]
            try:
                received_at = int(received_at or 0)
            except (ValueError, TypeError):
                received_at = 0
            # 去重：客户端发送超时会判定失败并重试，但服务器可能已经收到了
            # （超时发生在等响应时），重试就会变成第二份。用"设备+发件人+时间戳"
            # 判重，直接返回已有的 id，让客户端重试变成幂等操作。
            # （2026-10-15：无去重导致接收端出现重复消息。）
            if received_at > 0:
                for m in self.messages:
                    if (m.get("device") == device
                            and m.get("sender") == sender
                            and m.get("received_at") == received_at):
                        return m["id"]
            msg = {
                "id": self.next_id,
                "sender": sender,
                "body": body[:MAX_BODY_LEN],
                "received_at": received_at,
                "device": device,
                "note": (note or "")[:500],
                "server_at": int(time.time() * 1000),
            }
            self.next_id += 1
            self.messages.append(msg)
            if len(self.messages) > MAX_MESSAGES:
                self.messages = self.messages[-MAX_MESSAGES:]
            self._save()
            return msg["id"]

    def after(self, after_id, limit=200):
        with self.lock:
            msgs = [m for m in self.messages if m["id"] > after_id][-limit:]
            latest = self.messages[-1]["id"] if self.messages else 0
            return msgs, latest

    def stats(self):
        with self.lock:
            return len(self.messages), (self.messages[-1]["id"] if self.messages else 0)


STORE = Store()
TOKEN = ""


class Handler(BaseHTTPRequestHandler):
    server_version = "SmsRelay/3.0"

    def log_message(self, fmt, *args):
        sys.stdout.write("[relay] %s %s\n" % (self.address_string(), fmt % args))
        sys.stdout.flush()

    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authed(self):
        return self.headers.get("X-Relay-Token") == TOKEN

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path == BASE_PATH + "/health":
            count, latest = STORE.stats()
            self._send_json(200, {"ok": True, "count": count, "latest": latest})
            return
        if path == BASE_PATH + "/poll":
            if not self._authed():
                self._send_json(401, {"ok": False, "error": "bad token"})
                return
            try:
                after = int(parse_qs(parsed.query).get("after", ["0"])[0])
            except ValueError:
                after = 0
            msgs, latest = STORE.after(after)
            self._send_json(200, {"ok": True, "messages": msgs, "latest": latest})
            return
        self._send_json(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path != BASE_PATH + "/send":
            self._send_json(404, {"ok": False, "error": "not found"})
            return
        if not self._authed():
            self._send_json(401, {"ok": False, "error": "bad token"})
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
        except ValueError:
            length = 0
        if length <= 0 or length > 65536:
            self._send_json(400, {"ok": False, "error": "bad length"})
            return
        try:
            data = json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            self._send_json(400, {"ok": False, "error": "bad json"})
            return
        sender = str(data.get("sender", "")).strip()
        body = str(data.get("body", "")).strip()
        if not body:
            self._send_json(400, {"ok": False, "error": "empty body"})
            return
        try:
            received_at = int(data.get("received_at", 0))
        except (ValueError, TypeError):
            received_at = 0
        msg_id = STORE.add(sender, body, received_at,
                           str(data.get("device", "")),
                           str(data.get("note", "")))
        self._send_json(200, {"ok": True, "id": msg_id})


def resolve_token(cli_token):
    if cli_token:
        return cli_token.strip()
    env = os.environ.get("SMS_RELAY_TOKEN", "").strip()
    if env:
        return env
    try:
        with open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                               "relay_token.txt"), "r", encoding="utf-8") as f:
            return f.readline().strip()
    except FileNotFoundError:
        return ""


def main():
    global TOKEN
    ap = argparse.ArgumentParser(description="SMS forwarder v3.0 relay")
    ap.add_argument("--token", default="", help="shared secret (or SMS_RELAY_TOKEN env / relay_token.txt)")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8765)
    args = ap.parse_args()
    TOKEN = resolve_token(args.token)
    if not TOKEN:
        print("[relay] FATAL: no token. Use --token, SMS_RELAY_TOKEN env, or relay_token.txt",
              file=sys.stderr)
        sys.exit(1)
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[relay] listening on {args.host}:{args.port}{BASE_PATH} "
          f"(store: {STORE_FILE}, messages: {STORE.stats()[0]})", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
