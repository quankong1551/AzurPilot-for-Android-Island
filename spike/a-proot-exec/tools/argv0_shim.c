// 用于 Spike A 的静态 AArch64 exec shim，使用调用方指定的 argv[0] 运行目标路径。
//
// 多调用二进制（例如 busybox）按 argv[0] 选择 applet，但 nativeLibraryDir 中的
// libbusybox.so 不能为每个 applet 改名。应用因此执行本 shim：
//
//     libspike_shim.so <argv0> <path> [args...]
//
// shim 再以 argv = [<argv0>, args...] 调用 execve(<path>, ...)。
//
// Static AArch64 exec shim for Spike A that runs a target path with caller-controlled argv[0].
//
// Multi-call binaries such as busybox select an applet from argv[0], but libbusybox.so in
// nativeLibraryDir cannot be renamed per applet. The app therefore invokes this shim:
//
//     libspike_shim.so <argv0> <path> [args...]
//
// The shim then calls execve(<path>, ...) with argv = [<argv0>, args...].
#include <stdio.h>
#include <unistd.h>

extern char **environ;

// 以 argv[1] 作为目标程序 argv[0] 执行 argv[2]；参数不足返回 2，execve 失败返回 127。
// Executes argv[2] with argv[1] as its argv[0]; returns 2 for missing arguments and 127 when
// execve fails.
int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <argv0> <path> [args...]\n", argv[0]);
        return 2;
    }
    const char *path = argv[2];
    char **out = argv + 1;
    for (int i = 1; i <= argc - 2; i++) {
        out[i] = argv[i + 2];
    }
    execve(path, out, environ);
    perror("execve");
    return 127;
}
