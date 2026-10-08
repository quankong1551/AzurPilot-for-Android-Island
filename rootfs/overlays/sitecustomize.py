"""仅在 AzurPilot Android 运行时启用进程枚举兼容层和宿主 OCR。

本文件被装进 venv site-packages 顶层，任何解释器启动都会经 site 机制自动导入，
因此必须用 AZURPILOT_ANDROID 环境变量门控：开发机与 CI 的裸 Python 不受影响；
命中时才导入 android_process_compat 并给 psutil 打桩；宿主另行注入 OCR 地址时
安装张量代理。APK 也将本文件放在 PYTHONPATH 根目录，供已有 rootfs 升级使用。

Enables process-enumeration compatibility and host OCR only inside the AzurPilot
Android runtime.

The file sits at the top of the venv's site-packages, so every interpreter
startup imports it automatically through the site mechanism; it must therefore
be gated on the AZURPILOT_ANDROID environment variable to leave bare
interpreters on dev machines and CI untouched. Only when the gate matches is
android_process_compat imported and psutil patched. A host OCR address enables the tensor
proxy. The APK also puts this file at the PYTHONPATH root to update existing rootfs installations.
"""

import os

if os.environ.get("AZURPILOT_ANDROID") == "1":
    from android_process_compat import install

    install()

    # 宿主未注入 OCR 地址时，保持旧 APK 和构建质量门的 CPU 行为。
    if os.environ.get("AZURPILOT_OCR_ADDRESS"):
        from android_ocr import install as install_ocr

        install_ocr()
