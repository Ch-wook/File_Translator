package kr.documentstudio.core
import org.junit.Assert.*
import org.junit.Test

class MarkdownEditingTest {
    private fun again(edit: TextEdit,command: String)=MarkdownEditing.apply(edit.text,edit.first,edit.last,command)
    @Test fun syntaxToolsKeepSelectionUnicodeAndLineBoundaries() {
        val bold=MarkdownEditing.apply("한글 내용",0,2,"bold");assertEquals("**한글** 내용",bold.text);assertEquals("한글",bold.text.substring(bold.first,bold.last))
        val tasks=MarkdownEditing.apply("앞\n항목 1\n항목 2\n뒤",2,12,"task");assertEquals("앞\n- [ ] 항목 1\n- [ ] 항목 2\n뒤",tasks.text)
        assertEquals("# ",MarkdownEditing.apply("",0,0,"heading").text)
        assertEquals("\u0060코드\u0060",MarkdownEditing.apply("",0,0,"code").text)
    }
    @Test fun inlineButtonsToggleOnlyTheirOwnDelimiters() {
        val bold=MarkdownEditing.apply("앞 한글 뒤",2,4,"bold")
        val italic=again(bold,"italic");assertEquals("앞 ***한글*** 뒤",italic.text)
        val strike=again(italic,"strike");assertEquals("앞 ***~~한글~~*** 뒤",strike.text)
        assertEquals(italic,again(strike,"strike"))
        assertEquals(bold,again(italic,"italic"))
        assertEquals("앞 한글 뒤",again(bold,"bold").text)
        assertEquals("앞 한글 뒤",MarkdownEditing.apply("앞 **한글** 뒤",2,8,"bold").text)
    }
    @Test fun headingsReplaceLevelsAndToggleWithoutDuplicatingMarkers() {
        val edit=MarkdownEditing.apply("# 첫째\n둘째\n유지",0,7,"heading3")
        assertEquals("### 첫째\n### 둘째\n유지",edit.text)
        assertEquals("첫째\n둘째\n유지",again(edit,"heading3").text)
        assertEquals("###### 첫째\n###### 둘째\n유지",again(edit,"heading6").text)
    }
    @Test fun listsRenumberAndConvertInsteadOfStackingPrefixes() {
        val edit=MarkdownEditing.apply("앞\n항목\n항목\n뒤",2,8,"number")
        assertEquals("앞\n1. 항목\n2. 항목\n뒤",edit.text)
        val task=again(edit,"task");assertEquals("앞\n- [ ] 항목\n- [ ] 항목\n뒤",task.text)
        assertEquals("앞\n항목\n항목\n뒤",again(task,"task").text)
    }
    @Test fun selectionEndingAtNewlineDoesNotChangeNextParagraph() {
        assertEquals("> 첫째\n둘째",MarkdownEditing.apply("첫째\n둘째",0,3,"quote").text)
        val empty=MarkdownEditing.apply("항목\n\n항목",0,6,"list");assertEquals("- 항목\n\n- 항목",empty.text)
        assertEquals("  - 안쪽",MarkdownEditing.apply("  안쪽",2,4,"list").text)
    }
    @Test fun codeFencesProtectEmbeddedBackticksAndNewToolsStoreSource() {
        assertEquals("\u0060\u0060a\u0060b\u0060\u0060",MarkdownEditing.apply("a\u0060b",0,3,"code").text)
        assertTrue(MarkdownEditing.apply("그림",0,2,"image").text.startsWith("![그림]("))
        assertEquals("\n\n---\n\n",MarkdownEditing.apply("",0,0,"rule").text)
    }
}
