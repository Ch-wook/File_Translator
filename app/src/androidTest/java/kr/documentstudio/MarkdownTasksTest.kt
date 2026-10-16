package kr.documentstudio

import kr.documentstudio.data.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownTasksTest {
    @Test fun realListTasksExcludeCodeEscapesAndProseAndToggleExactSource() {
        val source="- [ ] 첫 항목\r\n  - [x] **중첩**\r\n\r\n```\n- [ ] 코드\n```\n\\- [ ] 일반\n- \\[ ] 이스케이프\n문장 [ ]\n"
        val tasks=MarkdownTasks.tasks(source);assertEquals(2,tasks.size);assertFalse(tasks[0].checked);assertTrue(tasks[1].checked)
        val changed=MarkdownTasks.toggle(source,tasks[0].offset);assertEquals(source.replaceFirst("[ ]","[x]"),changed)
        assertEquals(source,MarkdownTasks.toggle(changed,tasks[0].offset))
        val html=MarkdownTasks.html(changed,"test");assertTrue(html.contains("studio-task://test/${tasks[0].offset}"));assertTrue(html.contains("aria-checked=\"true\""))
        val rendered=MarkdownRender.render(changed).toString();assertTrue(rendered.contains("☑ 첫 항목"));assertTrue(rendered.contains("☑ 중첩"));assertTrue(rendered.contains("- [ ] 코드"))
        assertThrows(IllegalStateException::class.java) {MarkdownTasks.toggle(source,source.indexOf("코드")-4)}
    }
}
