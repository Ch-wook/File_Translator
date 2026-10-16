package kr.documentstudio.core
import org.junit.Assert.*
import org.junit.Test

class MarkdownEditingTest {
    @Test fun syntaxToolsKeepSelectionUnicodeAndLineBoundaries() {
        val bold=MarkdownEditing.apply("한글 내용",0,2,"bold");assertEquals("**한글** 내용",bold.text);assertEquals("한글",bold.text.substring(bold.first,bold.last))
        val tasks=MarkdownEditing.apply("앞\n항목 1\n항목 2\n뒤",2,12,"task");assertEquals("앞\n- [ ] 항목 1\n- [ ] 항목 2\n뒤",tasks.text)
        assertEquals("# ",MarkdownEditing.apply("",0,0,"heading").text)
        assertEquals("`코드`",MarkdownEditing.apply("",0,0,"code").text)
    }
}
