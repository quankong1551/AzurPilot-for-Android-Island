// 以 root 启动 app_process 特权服务的最小 C 启动器。
//
// RootServiceStarter.kt 经 RootServiceStarter 传入的参数由本程序解析。本程序先以 root
// 打开诊断日志，再按默认安全模型降级为 shell UID/GID 组并 exec app_process；仅显式
// --keep-root 请求保留 root。它输出为 liblauncher.so，以便 Android 的 nativeLibraryDir
// 成为 targetSdk 约束下可 execve 的位置。
//
// Minimal C launcher for the app_process privileged service started as root.
// This program parses arguments supplied for RootServiceStarter.kt. It opens the diagnostic
// log as root, then follows the default safety model by dropping to the shell UID/GID groups
// before exec'ing app_process; only an explicit --keep-root request retains root. It is output
// as liblauncher.so so Android's nativeLibraryDir is executable under targetSdk restrictions.

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define LOG_TAG "RootLauncher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Android 系统 app_process 绝对路径；RootServiceStarter 通过该入口建立 Java 世界。
// Absolute Android app_process path; RootServiceStarter enters the Java world through it.
static const char *kAppProcessPath = "/system/bin/app_process";

// 默认降级目标。root 后端只保留启动所需特权，不将长期服务无条件留在 root。
// Default demotion target. The root backend retains only startup privilege and does not leave a
// long-lived service running as root unconditionally.
static const uid_t kShellUid = 2000;

// shell 进程需要的补充 GID。降级前设置完整组集，保留媒体、存储、网络、输入和日志访问，
// 而不维持 root UID。数值来自 Android 平台 AID 定义，不能按应用包名推导。
//
// Supplemental GIDs required by a shell process. Setting the full group set before demotion
// retains media, storage, network, input, and log access without retaining root UID. Values come
// from Android platform AID definitions and cannot be derived from the app package.
static const gid_t kRequiredShellGids[] = {
        2000, /* shell           */
        1002, /* bluetooth       */
        1004, /* input           */
        1005, /* audio           */
        1007, /* log             */
        1011, /* adb             */
        1013, /* media           */
        1015, /* sdcard_rw       */
        1024, /* mtp             */
        1028, /* sdcard_r        */
        1065, /* reserved_disk   */
        1078, /* ext_data_rw     */
        1079, /* ext_obb_rw      */
        1096, /* update_engine_log */
        3001, /* net_bt_admin    */
        3002, /* net_bt          */
        3003, /* inet            */
        3006, /* net_bw_stats    */
        3007, /* net_bw_acct     */
        3009, /* readproc        */
        3010, /* wakelock        */
        3011, /* uhid            */
        3012, /* readtracefs     */
        3013, /* virtualmachine  */
};

// 描述 RootServiceStarter 所需的 app_process 启动参数。
//
// 所有字符串均借用 argv 存储并只在本进程存活期间使用；keep_root 仅能由 root 后端显式
// 请求，默认 false。
//
// Describes app_process launch arguments required by RootServiceStarter.
// All strings borrow argv storage and are used only during this process lifetime. keep_root can
// be requested only explicitly by the root backend and defaults to false.
typedef struct {
    const char *apk_path;
    const char *process_name;
    const char *starter_class;
    const char *token;
    const char *package_name;
    const char *service_class;
    const char *debug_name;
    const char *log_file;
    int uid;
    bool keep_root;
} LauncherArgs;

// 诊断文件日志。root 身份打开的 fd 在 UID 降级后保持有效，因此 Java stderr 与 launcher
// 事件都能落到应用可读文件。
//
// Diagnostic file logging. A descriptor opened as root remains valid after UID demotion, so both
// launcher events and Java stderr can reach an app-readable file.
static int g_log_fd = -1;

// 将一条已格式化事件附加到日志文件；日志不可用时静默降级为仅 logcat。
// Appends one formatted event to the log file; silently falls back to logcat-only when unavailable.
static void flogf(const char *fmt, ...) {
    if (g_log_fd < 0) return;
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf) - 1, fmt, ap);
    va_end(ap);
    if (n > 0) {
        size_t length = (size_t) n;
        // vsnprintf 截断时返回期望长度，必须夹紧后才能作为 buf 下标和写入长度使用。
        if (length > sizeof(buf) - 2) {
            length = sizeof(buf) - 2;
        }
        buf[length] = '\n';
        write(g_log_fd, buf, length + 1);
    }
}

// 同时写入 logcat 和文件日志，保证 app_process exec 失败也有可检索诊断。
// Writes to both logcat and the file log so app_process exec failures remain diagnosable.
#define LOG_IF(level_macro, level, fmt, ...) \
    do { level_macro(fmt, ##__VA_ARGS__); flogf("[" level "] " fmt, ##__VA_ARGS__); } while(0)
#define LOGFI(...) LOG_IF(LOGI, "I", __VA_ARGS__)
#define LOGFW(...) LOG_IF(LOGW, "W", __VA_ARGS__)
#define LOGFE(...) LOG_IF(LOGE, "E", __VA_ARGS__)

// 参数解析工具。启动器刻意只识别 --key=value 形式，避免把任意 argv 交给 app_process。
// Argument parsing helpers. The launcher deliberately accepts only --key=value forms so it does
// not pass arbitrary argv through to app_process.
static bool starts_with(const char *value, const char *prefix) {
    return strncmp(value, prefix, strlen(prefix)) == 0;
}

// 解析十进制 uid；拒绝空值和任何尾随字符。
// Parses a decimal uid and rejects an empty value or trailing characters.
static bool parse_int(const char *value, int *out) {
    char *end_ptr = NULL;
    long parsed = strtol(value, &end_ptr, 10);
    if (value[0] == '\0' || end_ptr == value || *end_ptr != '\0') return false;
    *out = (int) parsed;
    return true;
}

// 解析并验证启动器所需参数。
//
// 缺少任一连接令牌、服务类、目标应用或 uid 时返回 false；调用方在 fork 之前停止，避免产生
// 无法回传 Binder 的孤儿 app_process。
//
// Parses and validates required launcher arguments.
// Missing any connection token, service class, target app, or uid returns false; callers stop
// before fork so they cannot create an orphan app_process unable to return its Binder.
static bool parse_args(int argc, char **argv, LauncherArgs *out) {
    memset(out, 0, sizeof(*out));
    out->uid = -1;

    for (int i = 1; i < argc; ++i) {
        if (starts_with(argv[i], "--apk="))           out->apk_path      = argv[i] + 6;
        else if (starts_with(argv[i], "--process-name=")) out->process_name  = argv[i] + 15;
        else if (starts_with(argv[i], "--starter-class=")) out->starter_class = argv[i] + 16;
        else if (starts_with(argv[i], "--token="))     out->token         = argv[i] + 8;
        else if (starts_with(argv[i], "--package="))   out->package_name  = argv[i] + 10;
        else if (starts_with(argv[i], "--class="))     out->service_class = argv[i] + 8;
        else if (starts_with(argv[i], "--debug-name=")) out->debug_name   = argv[i] + 13;
        else if (starts_with(argv[i], "--log-file="))  out->log_file      = argv[i] + 11;
        else if (starts_with(argv[i], "--uid=")) {
            if (!parse_int(argv[i] + 6, &out->uid)) {
                LOGE("Invalid uid: %s", argv[i] + 6);
                return false;
            }
        }
        else if (strcmp(argv[i], "--keep-root") == 0) out->keep_root = true;
    }

    return out->apk_path != NULL
           && out->process_name != NULL
           && out->starter_class != NULL
           && out->token != NULL
           && out->package_name != NULL
           && out->service_class != NULL
           && out->uid >= 0;
}

// 拼接一个 app_process --key=value 参数；调用者负责 free 返回值。
// Joins one app_process --key=value argument; callers own and free the result.
static char *format_arg(const char *prefix, const char *value) {
    size_t size = strlen(prefix) + strlen(value) + 1;
    char *out = (char *) malloc(size);
    if (out == NULL) { LOGE("malloc failed: %s", strerror(errno)); return NULL; }
    snprintf(out, size, "%s%s", prefix, value);
    return out;
}

// 设置 CLASSPATH 并替换子进程为 app_process / RootServiceStarter。
//
// 仅在 fork 后的子进程调用。stderr 重定向在 exec 前完成，因此 Java 启动异常会写入同一诊断
// 文件；execv 成功后此函数不会返回。
//
// Sets CLASSPATH and replaces the child with app_process / RootServiceStarter.
// Only the post-fork child calls this. stderr is redirected before exec so Java startup failures
// enter the same diagnostic file; this function never returns after a successful execv.
static void exec_app_process(const LauncherArgs *args) {
    char uid_text[32];
    char *nice_name_arg = NULL, *token_arg = NULL, *package_arg = NULL;
    char *service_arg = NULL, *uid_arg = NULL, *debug_arg = NULL;
    char *exec_args[11];
    size_t index = 0;

    snprintf(uid_text, sizeof(uid_text), "%d", args->uid);

    nice_name_arg = format_arg("--nice-name=", args->process_name);
    token_arg     = format_arg("--token=",     args->token);
    package_arg   = format_arg("--package=",   args->package_name);
    service_arg   = format_arg("--class=",     args->service_class);
    uid_arg       = format_arg("--uid=",       uid_text);
    if (args->debug_name != NULL)
        debug_arg = format_arg("--debug-name=", args->debug_name);

    if (!nice_name_arg || !token_arg || !package_arg || !service_arg || !uid_arg
        || (args->debug_name != NULL && !debug_arg)) {
        free(nice_name_arg); free(token_arg); free(package_arg);
        free(service_arg);   free(uid_arg);   free(debug_arg);
        exit(1);
    }

    if (setenv("CLASSPATH", args->apk_path, 1) != 0) {
        LOGFE("setenv(CLASSPATH) failed: %s", strerror(errno));
        exit(1);
    }

    exec_args[index++] = (char *) kAppProcessPath;
    exec_args[index++] = (char *) "/system/bin";
    exec_args[index++] = nice_name_arg;
    exec_args[index++] = (char *) args->starter_class;
    exec_args[index++] = token_arg;
    exec_args[index++] = package_arg;
    exec_args[index++] = service_arg;
    exec_args[index++] = uid_arg;
    if (debug_arg != NULL) exec_args[index++] = debug_arg;
    exec_args[index] = NULL;

    LOGFI("execv: %s CLASSPATH=%s nice-name=%s",
          kAppProcessPath, args->apk_path, args->process_name);

    // root 身份打开后降权仍可使用该 fd，捕获 Java 异常与 System.err 输出。
    if (g_log_fd >= 0) {
        dup2(g_log_fd, STDERR_FILENO);
    }

    execv(kAppProcessPath, exec_args);
    LOGE("execv(%s) failed: %s", kAppProcessPath, strerror(errno));
    free(nice_name_arg); free(token_arg); free(package_arg);
    free(service_arg);   free(uid_arg);   free(debug_arg);
    exit(1);
}

// 解析参数、准备日志、按默认策略降权，并监督 app_process 子进程。
//
// --keep-root 只跳过 UID/GID 降级，不改变 Binder 回传或退出监督。父进程等待子进程，让启动
// 调用方能获得明确的成功或失败状态。
//
// Parses arguments, prepares logging, applies default privilege demotion, and supervises the
// app_process child. --keep-root skips only UID/GID demotion; it does not change Binder return or
// exit supervision. The parent waits so the starter receives an explicit success or failure state.
int main(int argc, char **argv) {
    LauncherArgs args = {0};

    if (!parse_args(argc, argv, &args)) {
        LOGE("Missing required launcher args");
        return 1;
    }

    // 以 root 打开日志文件；降权后 fd 仍有效，且 0644 让 app 进程可读。
    if (args.log_file != NULL) {
        g_log_fd = open(args.log_file, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (g_log_fd < 0) {
            LOGW("Cannot open log file %s: %s", args.log_file, strerror(errno));
        } else {
            // 文件权限须允许随后以 app 身份运行的 Java 进程读取同一诊断日志。
            fchmod(g_log_fd, 0644);
        }
    }

    LOGFI("launcher start: apk=%s uid=%d", args.apk_path, args.uid);

    pid_t child = fork();
    if (child < 0) {
        LOGFE("fork failed: %s", strerror(errno));
        return 1;
    }

    if (child == 0) {
        if (!args.keep_root) {
            static const size_t kGidCount =
                    sizeof(kRequiredShellGids) / sizeof(kRequiredShellGids[0]);

            int sg_ret = setgroups((int) kGidCount, kRequiredShellGids);
            if (sg_ret != 0) {
                LOGFW("setgroups(%zu gids) failed: %s — continuing", kGidCount, strerror(errno));
            } else {
                LOGFI("setgroups(%zu gids): ok", kGidCount);
            }

            if (setresgid(kShellUid, kShellUid, kShellUid) != 0) {
                LOGFE("setresgid(%u) failed: %s", (unsigned) kShellUid, strerror(errno));
                _exit(1);
            }
            LOGFI("setresgid(%u): ok", (unsigned) kShellUid);

            if (setresuid(kShellUid, kShellUid, kShellUid) != 0) {
                LOGFE("setresuid(%u) failed: %s", (unsigned) kShellUid, strerror(errno));
                _exit(1);
            }
            LOGFI("setresuid(%u): ok — exec app_process", (unsigned) kShellUid);
        }

        exec_app_process(&args);
        _exit(1);
    }

    int status = 0;
    waitpid(child, &status, 0);

    if (WIFEXITED(status) && WEXITSTATUS(status) == 0) {
        LOGFI("child exited cleanly");
        return 0;
    }

    LOGFE("child exited with status=%d", status);
    return 1;
}
