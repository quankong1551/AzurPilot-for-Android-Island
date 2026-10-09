#!/usr/bin/env python3
"""裁剪 Python wheel 的 ELF 调试信息，保留完整的托管解释器分发目录。

Compacts Python wheel ELF files while preserving the complete managed interpreter distribution.
"""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile


def dynamic_symbols(path, readelf):
    """比较动态符号的完整语义，段编号用段名解析以允许删除调试段后的重新编号。

    Reads full dynamic-symbol semantics, resolving section numbers by name after debug stripping.
    """
    environment = {**os.environ, "LC_ALL": "C"}
    sections = subprocess.check_output([readelf, "--sections", "--wide", str(path)], env=environment)
    symbols = subprocess.check_output([readelf, "--dyn-syms", "--wide", str(path)], env=environment)
    return parse_dynamic_symbols(symbols, sections)


def parse_dynamic_symbols(symbols, sections):
    """保留符号顺序、地址、大小、类型、绑定、可见性、所属段和版本化名称。

    Preserves order, addresses, sizes, types, bindings, visibility, sections, and versioned names.
    """
    names = {match[1]: match[2] for match in re.finditer(rb"^\s*\[\s*(\d+)\]\s+(\S+)", sections, re.M)}
    result = []
    for line in symbols.splitlines():
        fields = line.split(maxsplit=7)
        if len(fields) < 7 or not fields[0].endswith(b":") or not fields[0][:-1].isdigit():
            continue
        section = fields[6]
        if section.isdigit():
            if section not in names:
                raise ValueError("Dynamic symbol refers to an unknown ELF section")
            section = names[section]
        result.append(tuple(fields[1:6]) + (section, fields[7] if len(fields) == 8 else b""))
    return tuple(result)


def elf_identity(path):
    """读取 ELF 位数、字节序和机器类型，防止宿主 strip 处理异架构设备缓存。

    Reads ELF class, byte order, and machine type to keep native strip off foreign device caches.
    """
    with Path(path).open("rb") as stream:
        header = stream.read(20)
    if len(header) < 20 or header[:4] != b"\x7fELF" or header[4] not in (1, 2) or header[5] not in (1, 2):
        return None
    machine = int.from_bytes(header[18:20], "little" if header[5] == 1 else "big")
    return header[4], header[5], machine


def compact(root, strip="strip", readelf="readelf"):
    """只处理 rootfs 内的虚拟环境，返回逻辑字节节省量。

    Processes only virtual environments inside the rootfs and returns logical savings.
    """
    root = Path(root).resolve(strict=True)
    if root == Path(root.anchor) or not (root / "opt/azurpilot").is_dir():
        raise ValueError("Expected a staged AzurPilot rootfs, not a filesystem root")
    roots = [root / "opt/azurpilot/.venv", root / "opt/azurpilot-venv"]
    python_distribution = root / "opt/uv-python"
    for directory in [*roots, python_distribution]:
        if (directory.exists() or directory.is_symlink()) and not directory.resolve().is_relative_to(root):
            raise ValueError("Python distribution must stay inside the staged rootfs")
    # uv 的可重定位 CPython 经 strip 后可能损坏版本信息；动态符号相同也不足以保证可运行。
    # 保留解释器、libpython 和标准库扩展，只裁剪 wheel，并继续验证分发目录没有越界。
    native = elf_identity(sys.executable)
    if native is None:
        raise ValueError("Compaction requires native ELF Python and binutils")
    result = {"elf_files": 0, "compacted_files": 0, "saved_bytes": 0,
              "skipped_foreign_elf_files": 0, "skipped_symbol_changes": 0,
              "symbol_change_files": [], "largest_savings": []}
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
                result["elf_files"] += 1
                identity = elf_identity(path)
                if identity is None:
                    raise ValueError(f"Invalid ELF header: {path.relative_to(root)}")
                # uiautomator2 缓存含多种 Android ABI；原生 runner 的 strip 无法处理全部架构。
                if identity != native:
                    result["skipped_foreign_elf_files"] += 1
                    continue
                before = dynamic_symbols(path, readelf)
                descriptor, temporary_name = tempfile.mkstemp(prefix=".strip-", dir=parent)
                os.close(descriptor)
                temporary = Path(temporary_name)
                try:
                    # uv 的 wheel 可能硬链接到宿主缓存；先复制，避免裁剪污染缓存和其他镜像。
                    shutil.copy2(path, temporary)
                    subprocess.run([strip, "--strip-unneeded", "--", str(temporary)], check=True)
                    if before != dynamic_symbols(temporary, readelf):
                        # 某些 patchelf wheel 的 LOCAL SECTION 标注会被 GNU strip 改坏；保留原库。
                        result["skipped_symbol_changes"] += 1
                        if len(result["symbol_change_files"]) < 20:
                            result["symbol_change_files"].append(str(path.relative_to(root)))
                        continue
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
