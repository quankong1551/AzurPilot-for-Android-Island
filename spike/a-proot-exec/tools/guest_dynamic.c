// 用于 Spike A 的动态链接 AArch64 guest。
//
// 它模拟产品场景：复制到 app 数据目录的 ELF 负载在 `proot -r` 中运行时，需要解析
// 解释器（PT_INTERP=/system/bin/linker64）和 libc。传入 `--exec <path> [args...]` 时，
// 它会执行子进程而不输出探针结果，用来验证 proot 根目录内由 guest 发起的 execve，
// 即 AzurPilot 启动 Python 子进程的路径。
//
// Dynamically linked AArch64 guest for Spike A.
//
// It mirrors the product scenario: an ELF payload copied into app data requires its interpreter
// (PT_INTERP=/system/bin/linker64) and libc while running under `proot -r`. With
// `--exec <path> [args...]`, it executes the child instead of printing a probe result, testing a
// guest-initiated execve inside the proot rootfs, which is the AzurPilot Python-subprocess path.
#include <stdio.h>
#include <string.h>
#include <sys/utsname.h>
#include <unistd.h>

// 输出动态 guest 的系统探针；`--exec` 时转而执行给定子程序，失败返回 126。
// Prints a dynamic-guest system probe; with `--exec`, executes the supplied child and returns 126
// if that exec fails.
int main(int argc, char **argv) {
    if (argc >= 3 && strcmp(argv[1], "--exec") == 0) {
        execv(argv[2], &argv[2]);
        perror("nested execv");
        return 126;
    }
    struct utsname u;
    uname(&u);
    printf("DYNAMIC_GUEST_OK pid=%d machine=%s release=%s\n", (int)getpid(), u.machine, u.release);
    fflush(stdout);
    return 0;
}
