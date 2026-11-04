package kr.documentstudio.core
import java.io.File
import java.io.ByteArrayInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.DocumentEntry
import org.apache.poi.poifs.filesystem.DocumentInputStream
import kr.dogfoot.hwplib.reader.HWPReader
import kr.dogfoot.hwpxlib.reader.HWPXReader
/** Diagnostic only; acceptance still requires the separate independent round-trip verifier. */
private class TableTraceReader(bytes:ByteArray, version:Long):kr.dogfoot.hwplib.util.compoundFile.reader.StreamReader() {
 init {
  this.`is`=ByteArrayInputStream(bytes);this.size=bytes.size.toLong()
  this.fileVersion=kr.dogfoot.hwplib.`object`.fileheader.FileVersion().apply {setVersion(version)}
 }
 override fun readRecordHeader():kr.dogfoot.hwplib.`object`.RecordHeader {
  val h=super.readRecordHeader()
  println("TABLE_TRACE pos="+currentPosition+" tag="+h.tagID+" level="+h.level+" size="+h.size)
  return h
 }
}
object TableSaveDiagnostic {
 @JvmStatic fun main(args:Array<String>) {
  val dir=File(args.single())
  for(ext in listOf("hwp","hwpx")){
   val file=File(dir,"table-save."+ext)
   runCatching {if(ext=="hwp")requireNotNull(HWPReader.fromFile(file)) else requireNotNull(HWPXReader.fromFile(file))}
     .onSuccess {println("TABLE_DIAGNOSTIC_READER_OK "+ext)}
     .onFailure {println("TABLE_DIAGNOSTIC_READER_FAILURE "+ext+" "+it.stackTraceToString())}
  }
  POIFSFileSystem(File(dir,"table-save.hwp"),true).use {fs->
   val packed=DocumentInputStream((fs.root.getEntry("BodyText") as DirectoryEntry).getEntry("Section0") as DocumentEntry).use {it.readBytes()}
   val bytes=InflaterInputStream(ByteArrayInputStream(packed),Inflater(true)).use {it.readBytes()}
   val header=fs.root.createDocumentInputStream("FileHeader").use {it.readBytes()}
   val version=(0..3).fold(0L){n,i->n or ((header[32+i].toLong() and 255) shl (i*8))}
   val reader=TableTraceReader(bytes,version)
   runCatching {kr.dogfoot.hwplib.reader.bodytext.ForSection.read(kr.dogfoot.hwplib.`object`.bodytext.Section(),reader)}
     .onSuccess {println("TABLE_TRACE_OK")}
     .onFailure {println("TABLE_TRACE_FAILURE "+reader.currentRecordHeader.tagID+" "+it.stackTraceToString())}
   fun u32(at:Int)=(0..3).fold(0L){n,i->n or ((bytes[at+i].toLong() and 255) shl (i*8))}
   var offset=0;var records=0
   while(offset+4<=bytes.size && records++<200){
    val word=u32(offset);val tag=(word and 1023).toInt();val level=((word shr 10) and 1023).toInt();var size=(word shr 20).toInt();offset+=4
    if(size==4095){size=u32(offset).toInt();offset+=4}
    require(size>=0 && offset+size<=bytes.size)
    println("HWP_RECORD tag=$tag level=$level size=$size data="+bytes.copyOfRange(offset,offset+minOf(size,48)).joinToString(""){"%02x".format(it.toInt() and 255)})
    offset+=size
   }
  }
 }
}
