package com.muzhou.droidagent

import com.muzhou.droidagent.ui.markdown.MdBlock
import com.muzhou.droidagent.ui.markdown.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun parsesHeadingsParagraphsAndCode() {
        val blocks = parseMarkdown(
            """
            # 标题

            第一段
            第二行

            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertEquals("kotlin", (blocks[2] as MdBlock.Code).language)
        assertTrue((blocks[2] as MdBlock.Code).code.contains("val x = 1"))
    }

    @Test
    fun keepsUnclosedFenceAsCodeForStreaming() {
        val blocks = parseMarkdown("```python\nprint('hi')")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.Code
        assertEquals("python", code.language)
        assertTrue(code.code.contains("print"))
    }

    @Test
    fun parsesListAndTable() {
        val blocks = parseMarkdown(
            """
            - 一
            - 二

            | A | B |
            |---|---|
            | 1 | 2 |
            """.trimIndent(),
        )
        assertTrue(blocks[0] is MdBlock.ListItem)
        assertTrue(blocks[1] is MdBlock.ListItem)
        val table = blocks[2] as MdBlock.Table
        assertEquals(listOf("A", "B"), table.header)
        assertEquals(listOf("1", "2"), table.rows.first())
    }
}
