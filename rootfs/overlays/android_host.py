#!/usr/bin/env python3
"""让 Android App 成为 WebUI 进程树的生命周期属主。

rootfs 的入口进程：由 app 侧 ProotHost 经 PRoot 拉起，chdir 到 /opt/azurpilot 后
以 venv Python 启动上游 gui.py（WebUI 与 WebSocket 网关，监听 127.0.0.1:25548）。
本进程退出或父进程消亡（stdin 管道 EOF）都会终结整棵子进程组，运行时因此不会
在 app 侧死亡后变成孤儿。

Makes the Android app the lifecycle owner of the WebUI process tree.

This is the rootfs entry process: spawned by the app-side ProotHost through
PRoot, it chdirs to /opt/azurpilot and starts the upstream gui.py with the venv
Python (WebUI and WebSocket gateway listening on 127.0.0.1:25548). Its own exit
or the parent's death (EOF on the stdin pipe) tears down the whole child
process group, so the runtime cannot outlive the app as an orphan.
"""

import os
import signal
import stat
import subprocess
import sys
import threading


def main():
    """拉起 WebUI 子进程并守望其整个生命周期，返回子进程退出码。

    Spawns the WebUI child, supervises it for its whole lifetime, and returns
    the child's exit code.
    """
    root = os.path.dirname(os.path.abspath(__file__))
    # 上游按相对路径解析 config / frontend 等资源，必须先落到安装目录再启动。
    os.chdir(root)
    child = subprocess.Popen(
        [os.path.join(root, '.venv/bin/python'), 'gui.py', '--host', '127.0.0.1', '--port', '25548'],
        cwd=root, stdin=subprocess.DEVNULL, start_new_session=True,
    )
    closing = threading.Event()

    def stop(*_):
        # 幂等：信号与 stdin EOF 可能同时到达，清理只执行一次。
        if closing.is_set():
            return
        closing.set()
        try:
            os.killpg(child.pid, signal.SIGTERM)
        except ProcessLookupError:
            # 进程组已不存在，无需再收尸。
            return
        try:
            child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL)
            child.wait()

    def watch_stdin():
        # 阻塞读管道直到 EOF；EOF 即 app 侧已死，是整个运行时的停机信号。
        try:
            sys.stdin.buffer.read()
        finally:
            stop()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        # 只有 stdin 真是管道（app 侧 keepalive）才起守望线程；tty 或 /dev/null 无 EOF 契约可言。
        if stat.S_ISFIFO(os.fstat(sys.stdin.fileno()).st_mode):
            threading.Thread(target=watch_stdin, name='android-parent-watch', daemon=True).start()
    except (OSError, ValueError):
        # 个别环境拿不到 stdin 的 fileno/fstat，退化为仅由信号驱动停机。
        pass
    code = child.wait()
    stop()
    return code


if __name__ == '__main__':
    sys.exit(main())
