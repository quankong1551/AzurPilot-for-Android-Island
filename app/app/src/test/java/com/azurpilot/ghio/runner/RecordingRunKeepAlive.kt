package com.azurpilot.ghio.runner

/**
 * 记录保活被拉起几次；单测里替掉前台服务
 *
 * Fake [RunKeepAlive] recording how many times keep-alive was started;
 * stands in for the foreground service in unit tests. Shared by
 * [RunLauncherTest] and [com.azurpilot.ghio.session.SessionViewModelTest].
 */
class RecordingRunKeepAlive : RunKeepAlive {
    var startCount: Int = 0
        private set

    override fun start() {
        startCount++
    }
}
