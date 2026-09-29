package com.azurpilot.ghio.project

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 可控的 ProjectRepository，供 ViewModel 单测注入
 *
 * Fake of [ProjectRepository] whose [state] is driven by tests via [emit];
 * shared by [com.azurpilot.ghio.runner.RunLauncherTest],
 * [com.azurpilot.ghio.runner.FocusDispatcherTest],
 * [com.azurpilot.ghio.runner.RunLogRecorderTest] and
 * [com.azurpilot.ghio.session.SessionViewModelTest].
 */
class FakeProjectRepository(
    initial: ProjectState = ProjectState.Loading,
) : ProjectRepository {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<ProjectState> = _state.asStateFlow()

    var reloadCount: Int = 0
        private set

    fun emit(state: ProjectState) {
        _state.value = state
    }

    override suspend fun reload() {
        reloadCount++
    }
}
