"""本地 SOCKS5 代理，把流量经构建服务器中转访问 GitHub。

凭据（**绝不写入本文件**）——按以下顺序查找：
  1. 环境变量  STARCAM_SSH_HOST / STARCAM_SSH_USER / STARCAM_SSH_PASS
  2. 未入库文件 ~/.starcam/ssh-tunnel.env（HOST=/USER=/PASS= 三行，chmod 600）

红线：任何凭据都不得进入 git 跟踪范围（含历史）。含凭据的文件一律放
~/.starcam/ 并 chmod 600，或走环境变量；提交前跑 tools/check_secrets.py。


用法：
    python tools/gh_socks_tunnel.py &          # 监听 127.0.0.1:1080
    git -c http.proxy=socks5h://127.0.0.1:1080 push origin main

背景：本机直连 github.com 被阻断（curl 多次 000），而构建服务器稳定可达
（实测 3/3 HTTP 200）。这里用 paramiko 的 direct-tcpip 通道做「ssh -L」的
等价物，在本机 127.0.0.1:1080 起一个 SOCKS5 服务，让 git / 浏览器通过它访问
GitHub。仅监听回环地址，不对外暴露。
"""

import json
import select
import socket
import struct
import sys
import threading

import paramiko

def _load_credentials():
    """凭据：环境变量优先，其次 ~/.starcam/ssh-tunnel.env（未入库）。"""
    import os
    host = os.environ.get("STARCAM_SSH_HOST", "")
    user = os.environ.get("STARCAM_SSH_USER", "")
    pwd = os.environ.get("STARCAM_SSH_PASS", "")
    if not (host and user and pwd):
        cfg = os.path.join(os.path.expanduser("~"), ".starcam", "ssh-tunnel.env")
        try:
            with open(cfg, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    k, v = line.split("=", 1)
                    k = k.strip().upper()
                    if k == "HOST":
                        host = host or v.strip()
                    elif k == "USER":
                        user = user or v.strip()
                    elif k == "PASS":
                        pwd = pwd or v.strip()
        except OSError:
            pass
    if not (host and user and pwd):
        sys.exit(
            "缺少凭据。请设置环境变量 STARCAM_SSH_HOST/USER/PASS，或创建 "
            "~/.starcam/ssh-tunnel.env（HOST=/USER=/PASS= 三行，chmod 600）。"
            "切勿把凭据写进本文件——它受 git 跟踪。",
        )
    return host, user, pwd


SSH_HOST, SSH_USER, SSH_PASS = _load_credentials()
LISTEN = ("127.0.0.1", 1080)

transport = None
_lock = threading.Lock()


def connect_ssh():
    global transport
    t = paramiko.Transport((SSH_HOST, 22))
    t.connect(username=SSH_USER, password=SSH_PASS)
    with _lock:
        transport = t
    print(f"[ssh] 已连接 {SSH_HOST}", flush=True)
    return t


def pump(a, b):
    """双向转发，任一侧关闭即结束。"""
    socks = [a, b]
    try:
        while True:
            r, _, _ = select.select(socks, [], [], 60)
            if not r:
                break
            for s in r:
                try:
                    data = s.recv(65536)
                except Exception:
                    return
                if not data:
                    return
                other = b if s is a else a
                try:
                    other.sendall(data)
                except Exception:
                    return
    finally:
        for s in (a, b):
            try:
                s.close()
            except Exception:
                pass


def handle(client):
    try:
        # ---- SOCKS5 握手（无认证）----
        head = client.recv(2)
        if len(head) < 2 or head[0] != 0x05:
            client.close(); return
        nmethods = head[1]
        client.recv(nmethods)
        client.sendall(b"\x05\x00")

        # ---- 请求 ----
        req = client.recv(4)
        if len(req) < 4:
            client.close(); return
        _, cmd, _, atyp = req
        if cmd != 0x01:                      # 只支持 CONNECT
            client.sendall(b"\x05\x07\x00\x01" + b"\x00" * 6)
            client.close(); return
        if atyp == 0x01:                     # IPv4
            host = socket.inet_ntoa(client.recv(4))
        elif atyp == 0x03:                   # 域名
            ln = client.recv(1)[0]
            host = client.recv(ln).decode()
        elif atyp == 0x04:                   # IPv6
            host = socket.inet_ntop(socket.AF_INET6, client.recv(16))
        else:
            client.close(); return
        port = struct.unpack("!H", client.recv(2))[0]

        # ---- 经服务器建立通道 ----
        try:
            ch = transport.open_channel(
                "direct-tcpip", (host, port), ("127.0.0.1", 0), timeout=30)
        except Exception as e:
            print(f"[skip] {host}:{port} -> {str(e)[:70]}", flush=True)
            client.sendall(b"\x05\x05\x00\x01" + b"\x00" * 6)
            client.close(); return

        client.sendall(b"\x05\x00\x00\x01" + b"\x00" * 6)
        pump(client, ch)
    except Exception as e:
        print("[err]", str(e)[:90], flush=True)
        try:
            client.close()
        except Exception:
            pass


def main():
    connect_ssh()
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(LISTEN)
    srv.listen(128)
    print(f"[socks] 监听 {LISTEN[0]}:{LISTEN[1]}", flush=True)
    while True:
        try:
            c, addr = srv.accept()
        except KeyboardInterrupt:
            break
        threading.Thread(target=handle, args=(c,), daemon=True).start()


if __name__ == "__main__":
    main()
