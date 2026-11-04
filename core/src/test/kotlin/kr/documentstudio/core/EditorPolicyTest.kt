package kr.documentstudio.core

import org.junit.Assert.*
import org.junit.Test

class EditorPolicyTest {
    @Test fun everyFormatHasADedicatedEngineAndBinaryDocumentsNeverUsePlainText() {
        assertEquals(11,Format.entries.size)
        assertEquals(8,EditorPolicy.visualFormats.size)
        assertEquals(EditorEngine.HANGUL,EditorPolicy.engine(Format.HWP))
        assertEquals(EditorEngine.HANGUL,EditorPolicy.engine(Format.HWPX))
        assertEquals(EditorEngine.PDF,EditorPolicy.engine(Format.PDF))
        Format.entries.filter {it !in setOf(Format.TXT,Format.MD)}.forEach {assertFalse(EditorPolicy.engine(it) in setOf(EditorEngine.TEXT,EditorEngine.MARKDOWN))}
        assertFalse(EditorPolicy.available(Format.DOCX,29));assertTrue(EditorPolicy.available(Format.DOCX,30));assertTrue(EditorPolicy.available(Format.HWPX,29))
    }
}
