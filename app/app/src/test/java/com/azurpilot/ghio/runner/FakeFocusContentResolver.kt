package com.azurpilot.ghio.runner

/**
 * 正文原样返回：不涉及 `{image}` 与文件路径的用例用它，避免各自造一个空实现
 *
 * Fake [FocusContentResolver] that returns content unchanged; shared by
 * [FocusDispatcherTest], [RunLogRecorderTest] and
 * [com.azurpilot.ghio.session.SessionViewModelTest]. Used by cases that do
 * not involve `{image}` or file paths, so each need not build its own empty
 * implementation.
 */
object PassthroughFocusContentResolver : FocusContentResolver {
    override suspend fun resolve(content: String): String = content
}

/**
 * 把需要 IO 的两种形态换成固定结果，用来验调用方的顺序与落点
 *
 * Fake [FocusContentResolver] that replaces the two IO-bearing forms with
 * fixed results; used to verify the caller's ordering and landing spot.
 */
class RecordingFocusContentResolver(
    private val imageUri: String = "file:///tmp/shot.png",
    private val fileBody: String = "文件正文",
) : FocusContentResolver {

    val requests = mutableListOf<String>()

    override suspend fun resolve(content: String): String {
        requests += content
        return when {
            content.contains(FOCUS_IMAGE_PLACEHOLDER) -> content.replace(FOCUS_IMAGE_PLACEHOLDER, imageUri)
            else -> fileBody
        }
    }
}
