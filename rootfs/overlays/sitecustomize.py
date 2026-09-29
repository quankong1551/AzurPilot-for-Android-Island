"""仅在 AzurPilot Android 运行时启用进程枚举兼容层。

本文件被装进 venv site-packages 顶层，任何解释器启动都会经 site 机制自动导入，
因此必须用 AZURPILOT_ANDROID 环境变量门控：开发机与 CI 的裸 Python 不受影响；
命中时才导入 android_process_compat 并给 psutil 打桩。

Enables the process-enumeration compatibility layer only inside the AzurPilot
Android runtime.

The file sits at the top of the venv's site-packages, so every interpreter
startup imports it automatically through the site mechanism; it must therefore
be gated on the AZURPILOT_ANDROID environment variable to leave bare
interpreters on dev machines and CI untouched. Only when the gate matches is
android_process_compat imported and psutil patched.
"""

import os

if os.environ.get("AZURPILOT_ANDROID") == "1":
    from android_process_compat import install

    install()
