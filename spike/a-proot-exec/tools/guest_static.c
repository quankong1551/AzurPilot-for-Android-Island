// 用于 Spike A 的静态链接 AArch64 guest。
//
// 此最小 ELF 不依赖动态链接器或 guest 根文件系统库，用作 PRoot 执行链路的基线探针；
// 它将 uname 结果与自身 PID 输出给宿主日志。
//
// Statically linked AArch64 guest for Spike A.
//
// This minimal ELF needs neither a dynamic linker nor guest-rootfs libraries, so it provides a
// baseline probe for the PRoot execution path. It writes uname results and its PID to the host log.
#include <stdio.h>
#include <string.h>
#include <sys/utsname.h>
#include <unistd.h>

// 输出静态 guest 的系统探针并返回成功。
// Prints the static-guest system probe and returns success.
int main(void) {
    struct utsname u;
    memset(&u, 0, sizeof(u));
    uname(&u);
    printf("STATIC_GUEST_OK pid=%d sysname=%s machine=%s release=%s\n",
           (int)getpid(), u.sysname, u.machine, u.release);
    fflush(stdout);
    return 0;
}
