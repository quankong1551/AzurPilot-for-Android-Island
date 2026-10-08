#!/usr/bin/env python3
"""裁剪 Python 运行环境的 ELF 调试信息，保持动态链接接口与文件权限。

Compacts Python runtime ELF files while preserving dynamic interfaces and file permissions.
"""

import argparse
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile


def dynamic_symbols(path, readelf):
    """读取完整动态符号表用于前后比较。 / Reads the full dynamic symbol table for comparison."""
    return subprocess.check_output([readelf, "--dyn-syms", "--wide", str(path)])


def compact(root, strip="strip", readelf="readelf"):
    """只处理 rootfs 内的 Python 分发目录，返回逻辑字节节省量。

    Processes only Python distribution directories inside the rootfs and returns logical savings.
    """
    root = Path(root).resolve(strict=True)
    if root == Path(root.anchor) or not (root / "opt/azurpilot").is_dir():
        raise ValueError("Expected a staged AzurPilot rootfs, not a filesystem root")
    roots = [root / "opt/azurpilot/.venv", root / "opt/azurpilot-venv", root / "opt/uv-python"]
    for directory in roots:
        if (directory.exists() or directory.is_symlink()) and not directory.resolve().is_relative_to(root):
            raise ValueError("Python distribution must stay inside the staged rootfs")
    result = {"elf_files": 0, "compacted_files": 0, "saved_bytes": 0, "largest_savings": []}
    savings = []
    for directory in roots:
        if not directory.exists() or directory.is_symlink():
            continue
        for parent, dirs, files in os.walk(directory, followlinks=False):
            dirs[:] = sorted(name for name in dirs if not (Path(parent) / name).is_symlink())
            for name in sorted(files):
                path = Path(parent) / name
                info = path.lstat()
                if not stat.S_ISREG(info.st_mode):
                    continue
                if ".so" not in name and not info.st_mode & 0o111:
                    continue
                with path.open("rb") as stream:
                    if stream.read(4) != b"\x7fELF":
                        continue
                before = dynamic_symbols(path, readelf)
                result["elf_files"] += 1
                descriptor, temporary_name = tempfile.mkstemp(prefix=".strip-", dir=parent)
                os.close(descriptor)
                temporary = Path(temporary_name)
                try:
                    # uv 的 wheel 可能硬链接到宿主缓存；先复制，避免裁剪污染缓存和其他镜像。
                    shutil.copy2(path, temporary)
                    subprocess.run([strip, "--strip-unneeded", "--", str(temporary)], check=True)
                    if before != dynamic_symbols(temporary, readelf):
                        raise RuntimeError(f"Dynamic symbols changed: {path.relative_to(root)}")
                    saved = info.st_size - temporary.stat().st_size
                    if saved > 0:
                        os.utime(temporary, ns=(info.st_atime_ns, info.st_mtime_ns))
                        os.replace(temporary, path)
                        result["compacted_files"] += 1
                        result["saved_bytes"] += saved
                        savings.append({"path": str(path.relative_to(root)), "saved_bytes": saved})
                finally:
                    temporary.unlink(missing_ok=True)
    result["largest_savings"] = sorted(savings, key=lambda item: -item["saved_bytes"])[:20]
    return result


def main():
    """在打包前执行并保存构建报告。 / Runs before packaging and saves a build report."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("rootfs", type=Path)
    parser.add_argument("--strip", default="strip")
    parser.add_argument("--readelf", default="readelf")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = json.dumps(compact(args.rootfs, args.strip, args.readelf), indent=2) + "\n"
    if args.report:
        args.report.write_text(report, encoding="utf-8")
    print(report, end="")


if __name__ == "__main__":
    main()
