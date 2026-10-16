package kr.documentstudio.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.UUID

enum class Format(val extension: String, val mime: String, val label: String) {
    TXT("txt", "text/plain", "텍스트"), MD("md", "text/markdown", "Markdown"),
    PDF("pdf", "application/pdf", "PDF"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel"),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "PowerPoint"),
    HWPX("hwpx", "application/hwp+zip", "한글"),
    DOC("doc", "application/msword", "Word 97–2003"),
    XLS("xls", "application/vnd.ms-excel", "Excel 97–2003"),
    PPT("ppt", "application/vnd.ms-powerpoint", "PowerPoint 97–2003"),
    HWP("hwp", "application/x-hwp", "한글 바이너리")
}

data class TextContent(val text: String, val charset: String = "UTF-8", val bom: Boolean = false) {
    fun encode(text: String = this.text): ByteArray {
        val cs = Charset.forName(charset)
        val buffer = cs.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
        val body = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val prefix = if (!bom) byteArrayOf() else when (charset) {
            "UTF-8" -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            "UTF-16LE" -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            "UTF-16BE" -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> byteArrayOf()
        }
        return prefix + body
    }
    companion object {
        fun decode(bytes: ByteArray, fallback: String = "UTF-8"): TextContent {
            require(bytes.size <= 2 * 1024 * 1024) { "텍스트 편집은 2 MiB 이하 파일을 지원합니다." }
            val (encoding, skip) = when {
                bytes.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) -> "UTF-8" to 3
                bytes.take(2) == listOf(0xFF.toByte(), 0xFE.toByte()) -> "UTF-16LE" to 2
                bytes.take(2) == listOf(0xFE.toByte(), 0xFF.toByte()) -> "UTF-16BE" to 2
                else -> fallback to 0
            }
            val text = Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
            require(!text.contains('\u0000')) { "지원하지 않는 바이너리 파일입니다." }
            return TextContent(text, encoding, skip > 0)
        }
    }
}

object FormatDetector {
    fun detect(file: File, name: String, mime: String? = null, encoding: String = "UTF-8"): Format {
        val head = file.inputStream().use { stream -> ByteArray(1024).let { b -> b.copyOf(stream.read(b).coerceAtLeast(0)) } }
        if (head.toString(Charsets.ISO_8859_1).contains("%PDF-")) return Format.PDF
        if (head.take(8) == listOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()))
            return LegacyDocument.detect(file)
        if (head.size > 1 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) return PackageDocument.open(file).format
        val ext = name.substringAfterLast('.', "").lowercase()
        require(ext in listOf("txt", "md", "markdown") || mime?.startsWith("text/") == true) { "파일 헤더와 컨테이너가 지원 형식에 해당하지 않습니다." }
        TextContent.decode(file.readBytes(), encoding)
        return if (ext in listOf("md", "markdown") || mime == "text/markdown") Format.MD else Format.TXT
    }
}

data class InkPoint(val x: Float, val y: Float, val pressure: Float = 1f, val tilt: Float = 0f)
enum class InkTool { PEN, FOUNTAIN, PENCIL, BRUSH, HIGHLIGHTER, ERASER, LINE, ARROW, RECTANGLE, ELLIPSE, LASSO, HAND }
data class Stroke(val id: String = UUID.randomUUID().toString(), val anchor: String, val tool: InkTool,
    val color: Int, val width: Float, val points: List<InkPoint>,val opacity: Float=1f)
data class Edits(val text: String = "", val values: Map<String, String> = emptyMap(),
    val bold: Map<String, Boolean> = emptyMap(), val strokes: List<Stroke> = emptyList(),
    val styles: Map<String, Map<String,String>> = emptyMap())

data class DocumentChange(val type: String,val target: String="",val arguments: Map<String,String> = emptyMap())

class History<T>(private val limit: Int = 80) {
    private val previous = ArrayDeque<T>()
    private val next = ArrayDeque<T>()
    val canUndo get() = previous.isNotEmpty()
    val canRedo get() = next.isNotEmpty()
    fun record(value: T) { previous.addLast(value); if (previous.size > limit) previous.removeFirst(); next.clear() }
    fun undo(value: T): T { if (!canUndo) return value; next.addLast(value); return previous.removeLast() }
    fun redo(value: T): T { if (!canRedo) return value; previous.addLast(value); return next.removeLast() }
}
