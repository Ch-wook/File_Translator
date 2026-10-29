package kr.documentstudio.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.*

class ParagraphEditingTest {
    private fun temp()=Files.createTempFile("paragraph-",".bin").toFile().apply {deleteOnExit()}
    private fun docx(body: String): File {
        val source=temp();Templates.create(Format.DOCX,source);val out=temp()
        ZipFile(source).use {zip->ZipOutputStream(out.outputStream()).use {dest->zip.entries().toList().forEach {entry->
            dest.putNextEntry(ZipEntry(entry.name));dest.write(if(entry.name=="word/document.xml") "<w:document xmlns:w=\"${PackageDocument.W}\"><w:body>$body<w:sectPr/></w:body></w:document>".toByteArray() else zip.getInputStream(entry).readBytes());dest.closeEntry()
        }}}
        return out
    }
    private fun fields(vararg text: String)=text.mapIndexed {i,t->Field("word/document.xml#$i","text",t,paragraph="word/document.xml:p0")}
    private fun replace(f: List<Field>,next: String)=ParagraphEditing.replace(f,Edits(),next)
    @Test fun typingInsideAParagraphPreservesUnchangedRuns() {
        val f=fields("가나다"," ABC"," 끝")
        val updates=replace(f,"가나다 A수정BC 끝")
        assertEquals(mapOf(f[1].id to " A수정BC"),updates)
        assertEquals("가나다 A수정BC 끝",ParagraphEditing.text(f,Edits(values=updates)))
    }
    @Test fun replacementAcrossRunsIsOneAtomicChange() {
        val f=fields("first ","middle"," last")
        val updates=replace(f,"first replacement last")
        assertEquals("first replacement last",ParagraphEditing.text(f,Edits(values=updates)))
        assertFalse(updates.containsKey(f[0].id));assertFalse(updates.containsKey(f[2].id))
    }
    @Test fun deletingEverythingAndPastingMultilineUnicodeRetainsText() {
        val f=fields("가😀","나다"," 끝")
        val empty=Edits(values=replace(f,""))
        assertEquals("",ParagraphEditing.text(f,empty))
        val next=empty.copy(values=empty.values+ParagraphEditing.replace(f,empty,"😀\n한글\t붙여넣기"))
        assertEquals("😀\n한글\t붙여넣기",ParagraphEditing.text(f,next))
    }
    @Test fun styledEmptyRunReceivesTypingAtCaret() {
        val f=fields("before","","after")
        assertEquals(mapOf(f[1].id to "NEW"),replace(f,"beforeNEWafter"))
        val repeated=fields("before","","","after")
        assertEquals(repeated[2],ParagraphEditing.fieldAt(repeated,Edits(),6))
        assertEquals(mapOf(repeated[2].id to "NEW"),replace(repeated,"beforeNEWafter"))
    }
    @Test fun emojiReplacementNeverSplitsSurrogatePairs() {
        val f=fields("a😀","b")
        val updates=replace(f,"a😁b")
        assertEquals("a😁",updates[f[0].id]);assertEquals("a😁b",ParagraphEditing.text(f,Edits(values=updates)))
    }
    @Test fun paragraphsGroupRunsWithoutJoiningSeparateParagraphs() {
        val source=docx("<w:p><w:r><w:t>one</w:t></w:r><w:r><w:t> two</w:t></w:r></w:p><w:p><w:r><w:t>next</w:t></w:r></w:p>")
        val f=PackageDocument.open(source).sections.single().fields
        assertEquals(f[0].paragraph,f[1].paragraph);assertNotEquals(f[1].paragraph,f[2].paragraph)
    }
    @Test fun lineBreaksAndTabsSaveReopenAndSaveAgainWithoutDuplication() {
        for(format in listOf(Format.DOCX,Format.HWPX,Format.PPTX)) {
            val source=temp();Templates.create(format,source);val original=PackageDocument.open(source);val f=original.sections.first().fields.first();val out=temp()
            val value="첫 줄\n둘째\t열\n"
            original.write(out,Edits(values=mapOf(f.id to value)))
            val reopened=PackageDocument.open(out);val result=reopened.sections.first().fields
            assertEquals(format.name,value,result.takeWhile {it.paragraph==result.first().paragraph}.joinToString("") {it.text})
            run {
                val field=result.first();val again=temp();reopened.write(again,Edits(values=mapOf(field.id to "바뀜\n")))
                val third=PackageDocument.open(again).sections.first().fields
                assertEquals("바뀜\n"+result.drop(1).joinToString("") {it.text},third.joinToString("") {it.text})
            }
        }
    }
    @Test fun rangeFormattingPreservesPrefixSuffixDrawingsAndOtherRunStyles() {
        val source=docx("<w:p><w:r><w:rPr><w:i/></w:rPr><w:t>before SELECT after</w:t><w:drawing><w:test>keep</w:test></w:drawing></w:r></w:p>")
        val out=temp();PackageFeatures.apply(source,out,change=DocumentChange("text.format","word/document.xml",mapOf("paragraph" to "0","start" to "7","end" to "13","bold" to "true")))
        val f=PackageDocument.open(out).sections.single().fields
        assertEquals(listOf("before ","SELECT"," after"),f.map {it.text});assertEquals(listOf(false,true,false),f.map {it.bold});assertTrue(f.all {it.styles["italic"]=="true"})
        ZipFile(out).use {z->val doc=Xml.parse(z.getInputStream(z.getEntry("word/document.xml")).readBytes());assertEquals(1,Xml.elements(doc,"drawing").size)}
    }
    @Test fun formattingAtCaretCreatesAnInsertionRunWithoutChangingExistingText() {
        val source=docx("<w:p><w:r><w:t>leftright</w:t></w:r></w:p>")
        val out=temp();PackageFeatures.apply(source,out,change=DocumentChange("text.format","word/document.xml",mapOf("paragraph" to "0","start" to "4","end" to "4","bold" to "true")))
        val f=PackageDocument.open(out).sections.single().fields
        assertEquals(listOf("left","","right"),f.map {it.text});assertTrue(f[1].bold)
        val sized=temp();PackageFeatures.apply(out,sized,change=DocumentChange("text.format","word/document.xml",mapOf("paragraph" to "0","start" to "4","end" to "4","size" to "14")))
        val pending=PackageDocument.open(sized).sections.single().fields
        assertEquals(3,pending.size);assertTrue(pending[1].bold);assertEquals("14.0",pending[1].styles["size"])
        val typed=temp();PackageDocument.open(out).write(typed,Edits(values=replace(f,"leftNEWright")))
        val actual=PackageDocument.open(typed).sections.single().fields
        assertEquals("NEW",actual.single {it.bold}.text)
    }
    @Test fun hangulSelectedFormattingKeepsBreaksAndUnselectedText() {
        val source=temp();Templates.create(Format.HWPX,source)
        val original=PackageDocument.open(source);val field=original.sections.first().fields.first();val typed=temp()
        original.write(typed,Edits(values=mapOf(field.id to "앞\n선택\t뒤")))
        val out=temp();PackageFeatures.apply(typed,out,change=DocumentChange("text.format",original.sections.first().id,mapOf("paragraph" to "0","start" to "2","end" to "4","bold" to "true")))
        val fields=PackageDocument.open(out).sections.first().fields
        assertEquals("앞\n선택\t뒤",fields.joinToString("") {it.text})
        assertEquals("선택",fields.single {it.styles["bold"]=="true"}.text)
    }
    @Test fun styleIdsStillTargetOriginalRunsAfterMultilineEdits() {
        val source=docx("<w:p><w:r><w:t>first</w:t></w:r><w:r><w:t>second</w:t></w:r></w:p>")
        val f=PackageDocument.open(source).sections.single().fields;val out=temp()
        PackageDocument.open(source).write(out,Edits(values=mapOf(f[0].id to "a\nb"),styles=mapOf(f[1].id to mapOf("italic" to "true"))))
        val actual=PackageDocument.open(out).sections.single().fields
        assertEquals("second",actual.last().text);assertEquals("true",actual.last().styles["italic"]);assertTrue(actual.dropLast(1).all {it.styles["italic"]!="true"})
    }
}
