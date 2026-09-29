package com.azurpilot.ghio.project

import java.io.FileNotFoundException
import java.io.InputStream

/**
 * 内存 PI 包；没有归档，解包走清单 + [open]
 * [openCount] 用来判「这一轮到底解没解」，解包并发跑，计数要自己上锁
 *
 * Fake [PiPackage] held in memory; shared by [PiInstallerTest] and
 * [PiInstallCoordinatorTest]. There is no archive — unpacking reads the
 * manifest and then [open].
 *
 * [openCount] tells whether a round actually unpacked anything; unpacking
 * runs concurrently, so the counter takes its own lock.
 */
class MapPiPackage(private val files: Map<String, String>) : PiPackage {

    @Volatile
    var openCount = 0
        private set

    override fun manifest(): List<String> = files.keys.sorted()

    @Synchronized
    override fun open(path: String): InputStream {
        val content = files[path] ?: throw FileNotFoundException(path)
        openCount++
        return content.byteInputStream()
    }
}
