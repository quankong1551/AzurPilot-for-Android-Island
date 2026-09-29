"""保持虚拟显示实验的 TCP 客户端连接，并可选地保留前若干字节的原始输出。

命令行参数依次为端口、转储文件路径和最大转储字节数。脚本故意只持续读取并周期性报告流量，
用于验证服务端在客户端长时间存活或暂时空闲时的连接行为；它不解析协议内容。

Keeps a TCP client connection open for the virtual-display experiment and optionally preserves the
first portion of the raw output.

Its command-line arguments are the port, dump-file path, and maximum dump size. The script
intentionally only reads and periodically reports traffic, so it can observe server behavior while a
client stays connected or temporarily idle; it does not parse the protocol payload.
"""

import socket
import sys
import time

host = "127.0.0.1"
port = int(sys.argv[1]) if len(sys.argv) > 1 else 27183
dump_path = sys.argv[2] if len(sys.argv) > 2 else None
cap = int(sys.argv[3]) if len(sys.argv) > 3 else 2_000_000

s = socket.create_connection((host, port), timeout=20)
s.settimeout(30)
f = open(dump_path, "wb") if dump_path else None
total = 0
t0 = time.time()
last_report = 0.0
print(f"[hold] connected to {host}:{port}", flush=True)
while True:
    try:
        data = s.recv(65536)
    except socket.timeout:
        if time.time() - last_report > 30:
            print(f"[hold] idle {time.time()-t0:.0f}s, total={total}", flush=True)
            last_report = time.time()
        continue
    if not data:
        print(f"[hold] server closed after {total} bytes / {time.time()-t0:.1f}s", flush=True)
        break
    total += len(data)
    if f and f.tell() < cap:
        f.write(data[: max(0, cap - f.tell())])
    if time.time() - last_report > 15:
        print(f"[hold] {total} bytes, {time.time()-t0:.1f}s", flush=True)
        last_report = time.time()
if f:
    f.flush()
    f.close()
