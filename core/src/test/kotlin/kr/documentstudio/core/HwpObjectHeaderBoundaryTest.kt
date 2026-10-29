package kr.documentstudio.core

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlTable
import kr.dogfoot.hwplib.`object`.fileheader.FileVersion
import kr.dogfoot.hwplib.reader.bodytext.paragraph.control.gso.part.ForCtrlHeaderGso
import kr.dogfoot.hwplib.util.compoundFile.reader.StreamReader
import org.junit.Assert.*
import org.junit.Test

class HwpObjectHeaderBoundaryTest {
 private class Reader(bytes:ByteArray):StreamReader() {
  init {this.`is`=ByteArrayInputStream(bytes);this.size=bytes.size.toLong();setFileVersion(FileVersion().apply {setVersion(0x05000302L)})}
 }
 private fun checkTail(tail:ByteArray,prevent:Boolean=false,description:ByteArray?=null) {
  // Forty bytes include ctrlId and all mandatory common-object fields.
  val payload=ByteArray(40)+tail
  val bytes=ByteBuffer.allocate(4+payload.size+4).order(ByteOrder.LITTLE_ENDIAN)
    .putInt((payload.size shl 20) or (1 shl 10) or 71).put(payload).putInt((22 shl 20) or 66).array()
  val reader=Reader(bytes);reader.readRecordHeader();reader.readUInt4()
  val header=ControlTable().header
  ForCtrlHeaderGso.read(header,reader)
  assertEquals("Reader must stop at the exact record boundary",4L+payload.size,reader.currentPosition)
  assertEquals(prevent,header.isPreventPageDivide)
  if(description!=null)assertArrayEquals(description,header.explanation.bytes)
  val next=reader.readRecordHeader()
  assertEquals(66,next.tagID.toInt());assertEquals(0,next.level.toInt());assertEquals(22L,next.size)
 }
 @Test fun short42ByteHeaderDoesNotConsumeNextRecord()=checkTail(byteArrayOf(0,0))
 @Test fun full46ByteHeaderKeepsPreventPageDivide()=checkTail(byteArrayOf(1,0,0,0,0,0),true)
 @Test fun descriptionIsReadWithoutChangingNextHeader() {
  val text="표 설명".toByteArray(Charsets.UTF_16LE)
  val tail=ByteBuffer.allocate(6+text.size).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putShort((text.size/2).toShort()).put(text).array()
  checkTail(tail,description=text)
 }
}
