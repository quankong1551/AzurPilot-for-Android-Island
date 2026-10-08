"""验证裁剪后动态库可运行，且不会改写 uv 缓存或越过软链接。

Verifies that compacted libraries run without modifying uv caches or traversing symlinks.
"""

import ctypes
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "compact_runtime", Path(__file__).parents[1] / "build/compact-runtime.py")
compact_runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(compact_runtime)


@unittest.skipUnless(os.name == "posix" and all(shutil.which(tool) for tool in ("cc", "strip", "readelf")),
                     "Requires a native ELF compiler and binutils")
class CompactRuntimeTest(unittest.TestCase):
    """使用真实 ELF 测试导出符号和缓存隔离。 / Tests real ELF exports and cache isolation."""

    def test_library_runs_and_cache_and_symlinks_are_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "rootfs"
            package = root / "opt/azurpilot/.venv/lib"
            package.mkdir(parents=True)
            source = base / "source.c"
            source.write_text("int ocr_fixture(int value) { return value * 7; }\n")
            cache = base / "cache.so"
            subprocess.run(["cc", "-shared", "-fPIC", "-g", str(source), "-o", str(cache)], check=True)
            original = cache.read_bytes()
            original_mode = cache.stat().st_mode & 0o777
            target = package / "fixture.so"
            os.link(cache, target)
            os.symlink(cache, package / "external.so")
            os.symlink(base, package / "external_directory")
            report = compact_runtime.compact(root)
            self.assertGreater(report["saved_bytes"], 0)
            self.assertEqual(report["compacted_files"], 1)
            self.assertEqual(cache.read_bytes(), original)
            self.assertEqual(target.stat().st_mode & 0o777, original_mode)
            self.assertNotEqual(cache.stat().st_ino, target.stat().st_ino)
            self.assertTrue((package / "external.so").is_symlink())
            library = ctypes.CDLL(str(target))
            self.assertEqual(library.ocr_fixture(6), 42)

    def test_rejects_python_directory_outside_rootfs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "rootfs"
            (root / "opt/azurpilot").mkdir(parents=True)
            os.symlink(temporary, root / "opt/uv-python")
            with self.assertRaises(ValueError):
                compact_runtime.compact(root)


if __name__ == "__main__":
    unittest.main()
