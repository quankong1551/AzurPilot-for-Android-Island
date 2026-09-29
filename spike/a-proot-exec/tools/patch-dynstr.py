#!/usr/bin/env python3
"""就地缩短 Spike A AArch64 ELF 的 DT_NEEDED 字符串。

APK 的 `lib/<abi>/` 中只有名称符合 `lib*.so` 的库才能同时通过 AGP 打包并在安装时
提取到 `nativeLibraryDir`；其他名称通常仅在 debuggable 应用中由 AOSP 安装器保留。
Termux 构建引用版本化 SONAME（例如 `libtalloc.so.2`、`libbusybox.so.1.38.0`），本工具将
DT_NEEDED 字符串改为 `lib*.so` 名称，并要求目标文件同步改名。新字符串不得更长，剩余空间
以 NUL 填充，因此其他 dynstr 偏移保持不变，无需重建 ELF 头。

用法：
    python patch-dynstr.py <elf-file> <old-name> <new-name>

成功返回 0；新名称过长或旧字符串出现次数不为一次时拒绝写入。

Shortens a Spike A AArch64 ELF DT_NEEDED string in place.

Only library names matching `lib*.so` reliably pass both AGP packaging and install-time extraction
into `nativeLibraryDir`; other names are normally retained by the AOSP installer only for debuggable
apps. Termux builds reference versioned SONAMEs such as `libtalloc.so.2` and
`libbusybox.so.1.38.0`, so this tool rewrites the DT_NEEDED strings to `lib*.so` names and requires
matching file renames. The replacement must not be longer; NUL padding preserves every other dynstr
offset, so no ELF headers are rebuilt.

Usage:
    python patch-dynstr.py <elf-file> <old-name> <new-name>

Returns 0 on success and refuses to write when the replacement is longer or the old string does not
occur exactly once.
"""
import sys


def main() -> int:
    """校验命令行参数后，原子地以等长或更短名称替换一个 DT_NEEDED 条目。

    文件只在旧 NUL 终止字符串恰好出现一次且新名称可容纳时写回，避免含糊匹配破坏 ELF。

    Validates command-line arguments, then replaces one DT_NEEDED entry with an equal-or-shorter
    name atomically.

    The file is written only when the old NUL-terminated string occurs exactly once and the new
    name fits, preventing an ambiguous match from corrupting the ELF.

    Returns:
        0 on success, 1 for a rejected replacement, or 2 for invalid arguments.
    """
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
    old_bytes = old.encode() + b"\0"
    new_bytes = new.encode()
    if len(new_bytes) > len(old_bytes) - 1:
        print(f"ERROR: new name '{new}' does not fit in {len(old_bytes) - 1} bytes")
        return 1
    with open(path, "rb") as f:
        data = f.read()
    count = data.count(old_bytes)
    if count != 1:
        print(f"ERROR: expected exactly 1 occurrence of '{old}\\0', found {count}")
        return 1
    padded = new_bytes + b"\0" * (len(old_bytes) - len(new_bytes))
    with open(path, "wb") as f:
        f.write(data.replace(old_bytes, padded))
    print(f"OK {path}: '{old}' -> '{new}' (pad {len(old_bytes) - len(new_bytes)} NUL)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
