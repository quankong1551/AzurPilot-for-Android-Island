"""用真实 PRoot 验证无特权 guest、环境隔离及构建绑定的生命周期。

Verifies unprivileged guests, environment isolation, and binding lifetime with real PRoot.
"""

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


HELPER = Path(__file__).resolve().parents[1] / "build/guest-env.sh"


@unittest.skipUnless(os.name == "posix" and all(shutil.which(tool) for tool in
                     ("proot", "gcc", "ldd", "bash")), "Requires native Linux tools and PRoot")
class GuestEnvironmentTest(unittest.TestCase):
    """执行 guest 二进制，防止仅靠命令拼接检查漏掉权限错误。

    Executes guest binaries to catch permission errors beyond command construction.
    """

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / "rootfs"
        (self.root / "etc").mkdir(parents=True)
        (self.root / "guest-marker").write_text("guest-only\n")
        environment_binary = Path(shutil.which("env"))
        libraries = subprocess.check_output(["ldd", str(environment_binary)], text=True)
        for name in {str(environment_binary), *re.findall(r"/[^\s()]+", libraries)}:
            source = Path(name)
            target = self.root / source.relative_to("/")
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            target.chmod(0o755)
        source = self.base / "probe.c"
        source.write_text(r'''
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>
int main(int argc, char **argv) {
    if (argc > 1 && !strcmp(argv[1], "fail")) return 37;
    if (argc > 1 && !strcmp(argv[1], "child")) return getuid() == 0 ? 0 : 20;
    if (getuid() != 0 || geteuid() != 0) return 21;
    if (getenv("SECRET_FROM_RUNNER") || strcmp(getenv("HOME"), "/root")) return 22;
    if (strcmp(getenv("UV_CACHE_DIR"), "/opt/uv-cache")) return 23;
    FILE *marker = fopen("/guest-marker", "r");
    char value[32];
    if (!marker || !fgets(value, sizeof(value), marker) || strcmp(value, "guest-only\n")) return 24;
    fclose(marker);
    FILE *dns = fopen("/etc/resolv.conf", "r");
    if (!dns || !fgets(value, sizeof(value), dns)) return 25;
    fclose(dns);
    if (access("/proc/self/stat", R_OK) || access("/dev/null", W_OK)) return 26;
    FILE *cache = fopen("/opt/uv-cache/probe", "w");
    if (!cache) return 27;
    fputs("cached\n", cache);
    fclose(cache);
    pid_t child = fork();
    if (child == 0) { execl("/probe", "/probe", "child", NULL); _exit(28); }
    int status;
    if (child < 0 || waitpid(child, &status, 0) < 0 || !WIFEXITED(status) || WEXITSTATUS(status)) return 29;
    puts("GUEST_OK");
    return 0;
}
''')
        subprocess.run(["gcc", "-static", str(source), "-o", str(self.root / "probe")], check=True)

    def run_guest(self, commands, executor="proot"):
        """在独立 shell 中运行绑定设置及清理。 / Runs setup and cleanup in a separate shell."""
        environment = dict(os.environ, ROOTFS_DIR=str(self.root), WORK_DIR=str(self.base),
                           ROOTFS_EXECUTOR=executor, SECRET_FROM_RUNNER="must-not-leak")
        return subprocess.run(["bash", "-euc", 'source "$1"; trap cleanup_guest EXIT; ' + commands,
                               "guest-test", str(HELPER)], env=environment, text=True,
                              capture_output=True, timeout=30)

    def test_proot_runs_with_clean_environment_and_bound_cache(self):
        result = self.run_guest("setup_guest; guest /probe; cleanup_guest")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("GUEST_OK", result.stdout)
        self.assertEqual((self.base / "uv-cache/probe").read_text(), "cached\n")
        self.assertEqual(list((self.root / "opt/uv-cache").iterdir()), [])
        self.assertEqual((self.root / "etc/resolv.conf").read_bytes(), b"")

    def test_proot_bindings_do_not_create_kernel_mounts(self):
        result = self.run_guest('before=$(cat /proc/self/mountinfo); setup_guest; guest /probe; '
                                'cleanup_guest; [[ "$before" == "$(cat /proc/self/mountinfo)" ]]')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_guest_failure_status_is_preserved(self):
        result = self.run_guest("setup_guest; guest /probe fail")
        self.assertEqual(result.returncode, 37, result.stderr)

    def test_invalid_executor_is_rejected(self):
        result = self.run_guest("setup_guest", executor="invalid")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Unsupported rootfs executor", result.stderr)


if __name__ == "__main__":
    unittest.main()
