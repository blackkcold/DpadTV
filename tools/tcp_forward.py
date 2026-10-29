#!/usr/bin/env python3
"""把宿主机端口转发到目标服务，仅供模拟器访问用。

为什么需要：模拟器的 NAT 流量不会走 macOS 的 VPN 路由（局域网段走 utun），
所以 ICMP 通、TCP 不通。模拟器始终能访问 10.0.2.2（宿主机别名），
于是从 10.0.2.2:<listen> 转发到真实服务，测试与截图都能继续。

用法：python3 tools/tcp_forward.py --listen 8899 --target <你的服务器>:8899
"""
import argparse
import socket
import threading


def pipe(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                s.close()
            except OSError:
                pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", type=int, default=8899)
    ap.add_argument("--target", default="127.0.0.1:8899")
    a = ap.parse_args()
    host, port = a.target.rsplit(":", 1)
    port = int(port)

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", a.listen))
    srv.listen(64)
    print(f"forward 0.0.0.0:{a.listen} -> {host}:{port}", flush=True)
    while True:
        c, _ = srv.accept()
        try:
            u = socket.create_connection((host, port), timeout=8)
        except OSError as e:
            print("upstream fail:", e, flush=True)
            c.close()
            continue
        threading.Thread(target=pipe, args=(c, u), daemon=True).start()
        threading.Thread(target=pipe, args=(u, c), daemon=True).start()


if __name__ == "__main__":
    main()
