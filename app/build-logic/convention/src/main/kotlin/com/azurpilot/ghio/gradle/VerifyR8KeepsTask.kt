package com.azurpilot.ghio.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * 会被 native 代码或其他进程按字面名查找的类
 *
 * bridge.cpp 把它们持有为字符串常量，特权进程则用 `app_process --class=<name>` 拉起。
 * 改名在构建期完全不可见，只会在设备上暴露——在一个没有调试器的进程里，
 * 甚至可能要等一次运行真正进行中才暴露。
 *
 * Classes native code and other processes look up by their literal name.
 *
 * bridge.cpp holds them as string constants, and the privileged process is started with
 * `app_process --class=<name>`. Renaming one is invisible at build time and only shows up on
 * a device, inside a process without a debugger, possibly only once a run is actually under
 * way.
 */
internal val R8_CRITICAL_CLASSES = setOf(
    "com.azurpilot.ghio.bridge.NativeBridgeLib",
    "com.azurpilot.ghio.bridge.DriverClass",
    "com.azurpilot.ghio.remote.RemoteServiceImpl",
    "com.azurpilot.ghio.root.RootServiceStarter",
    "com.azurpilot.ghio.root.RootUserService",
)

/**
 * 断言 [R8_CRITICAL_CLASSES] 的 keep 规则仍然生效
 *
 * R8 不为保持原名的类输出映射：右侧出现别的名字意味着 keep 规则已失效；
 * 映射里整个缺失则意味着类已被 shrink 掉。
 *
 * Asserts the keep rules for [R8_CRITICAL_CLASSES] still hold.
 *
 * R8 omits identity mappings, so a class that appears with a different name on the right-hand
 * side lost its keep rule; a class missing from the mapping altogether was shrunk away.
 */
abstract class VerifyR8KeepsTask : DefaultTask() {

    /** release 变体产出的混淆映射文件 / The obfuscation mapping produced by the release variant. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    /** 需要保名验证的类全集 / The set of classes whose name must survive. */
    @get:Input
    abstract val criticalClasses: SetProperty<String>

    @TaskAction
    fun verify() {
        val file = mapping.get().asFile
        if (!file.isFile) return

        val renamed = mutableMapOf<String, String>()
        file.forEachLine { line ->
            if (line.startsWith(" ") || !line.endsWith(":")) return@forEachLine
            val parts = line.dropLast(1).split(" -> ")
            if (parts.size == 2) renamed[parts[0]] = parts[1]
        }

        val broken = criticalClasses.get().mapNotNull { name ->
            when (val mapped = renamed[name]) {
                null -> "$name was shrunk away"
                name -> null
                else -> "$name was renamed to $mapped"
            }
        }
        if (broken.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("R8 broke a name that native code or another process looks up literally:")
                    broken.forEach { appendLine("  - $it") }
                    appendLine("Check the keep rules in app/proguard-rules.pro before shipping this.")
                },
            )
        }
        logger.lifecycle("R8 keeps verified: ${criticalClasses.get().size} classes kept under their own name")
    }
}
