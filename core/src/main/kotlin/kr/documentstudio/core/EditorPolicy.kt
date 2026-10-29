package kr.documentstudio.core

enum class EditorEngine { TEXT, MARKDOWN, HANGUL, WRITER, CALC, IMPRESS, PDF }

/** A format never silently falls back to an extracted-text editor. */
object EditorPolicy {
    val visualFormats=setOf(Format.HWP,Format.HWPX,Format.DOC,Format.DOCX,Format.XLS,Format.XLSX,Format.PPT,Format.PPTX)
    fun engine(format: Format)=when(format) {
        Format.TXT->EditorEngine.TEXT
        Format.MD->EditorEngine.MARKDOWN
        Format.HWP,Format.HWPX->EditorEngine.HANGUL
        Format.DOC,Format.DOCX->EditorEngine.WRITER
        Format.XLS,Format.XLSX->EditorEngine.CALC
        Format.PPT,Format.PPTX->EditorEngine.IMPRESS
        Format.PDF->EditorEngine.PDF
    }
    fun available(format: Format,androidApi: Int)=when(engine(format)) {
        EditorEngine.WRITER,EditorEngine.CALC,EditorEngine.IMPRESS->androidApi>=30
        else->androidApi>=29
    }
}
