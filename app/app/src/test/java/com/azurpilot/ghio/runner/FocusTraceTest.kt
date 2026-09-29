package com.azurpilot.ghio.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FAILED = "Node.PipelineNode.Failed"
private const val SUCCEEDED = "Node.PipelineNode.Succeeded"

/**
 * 验证 focus 条目 trace 上报标记的解析：默认值、显式覆盖与无正文条目的产出
 *
 * Verifies parsing of the trace reporting flag on focus entries: defaults,
 * explicit overrides, and the emission of entries without content.
 */
class FocusTraceTest {

    @Test
    fun `缺省 trace 只有 Failed 算真`() {
        val failed = FocusParser.parse(FAILED, """{"focus":{"$FAILED":"炸了"}}""")!!
        assertTrue(failed.trace)

        val succeeded = FocusParser.parse(SUCCEEDED, """{"focus":{"$SUCCEEDED":"好了"}}""")!!
        assertFalse(succeeded.trace)
    }

    @Test
    fun `显式 trace 压过默认值`() {
        val off = FocusParser.parse(
            FAILED,
            """{"focus":{"$FAILED":{"content":"炸了","trace":false}}}""",
        )!!
        assertFalse(off.trace)

        val on = FocusParser.parse(
            SUCCEEDED,
            """{"focus":{"$SUCCEEDED":{"content":"好了","trace":true}}}""",
        )!!
        assertTrue(on.trace)
    }

    /**
     * 只配 trace 不配 content 是协议允许的写法，不能整条丢掉
     *
     * Configuring trace without content is allowed by the protocol and must
     * not drop the whole entry.
     */
    @Test
    fun `没有正文的 trace 条目照样产出`() {
        val entry = FocusParser.parse(SUCCEEDED, """{"focus":{"$SUCCEEDED":{"trace":true}}}""")!!
        assertTrue(entry.trace)
        assertFalse(entry.displayable)
        assertEquals("", entry.content)
    }

    @Test
    fun `既无正文又不上报的条目不往下游发`() {
        assertNull(FocusParser.parse(SUCCEEDED, """{"focus":{"$SUCCEEDED":{"display":"log"}}}"""))
    }

    @Test
    fun `事件名带进消息体`() {
        val entry = FocusParser.parse(FAILED, """{"focus":{"$FAILED":"炸了"}}""")!!
        assertEquals(FAILED, entry.message)
        assertTrue(entry.displayable)
    }
}
