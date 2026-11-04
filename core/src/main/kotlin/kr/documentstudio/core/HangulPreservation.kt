package kr.documentstudio.core

import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.*

/** Retain opaque package/compound entries the visual engine does not own. Edited body parts win. */
object HangulPreservation {
    fun seal(original: File,candidate: File,destination: File) {
        require(setOf(original.canonicalFile,candidate.canonicalFile,destination.canonicalFile).size==3)
        val format=FormatDetector.detect(original,"document.${if(isZip(original)) "hwpx" else "hwp"}")
        require(format in setOf(Format.HWP,Format.HWPX) && FormatDetector.detect(candidate,"document.${format.extension}")==format)
        val stage=File.createTempFile("hangul-seal-",".bin",destination.absoluteFile.parentFile)
        try {
            if(format==Format.HWPX) zip(original,candidate,stage) else compound(original,candidate,stage)
            require(stage.length() in 1..128L*1024*1024 && FormatDetector.detect(stage,"document.${format.extension}")==format)
            Files.move(stage.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING)
        } finally {stage.delete()}
    }
    private fun isZip(file: File)=file.inputStream().use {it.read()==0x50 && it.read()==0x4b}
    private fun zip(original: File,candidate: File,output: File) {
        fun owned(name: String)=name=="mimetype" || name.startsWith("Contents/section") || name in setOf("Contents/header.xml","Contents/content.hpf","META-INF/container.xml","META-INF/manifest.xml","version.xml") || name.startsWith("Preview/")
        ZipFile(original).use {before->ZipFile(candidate).use {after->ZipOutputStream(output.outputStream()).use {out->
            val names=after.entries().toList().map {it.name}.toSet()
            val entries=after.entries().toList().map {after to it}+before.entries().toList().filter {!it.isDirectory && it.name !in names && !owned(it.name)}.map {before to it}
            entries.forEach {(archive,entry)->
                val data=archive.getInputStream(entry).use {it.readBounded(48L*1024*1024)}
                out.putNextEntry(ZipEntry(entry.name).apply {time=entry.time;if(entry.name=="mimetype") {method=ZipEntry.STORED;size=data.size.toLong();crc=CRC32().apply {update(data)}.value}})
                out.write(data);out.closeEntry()
            }
        }}}
    }
    private fun compound(original: File,candidate: File,output: File) {
        POIFSFileSystem(original,true).use {before->POIFSFileSystem(candidate.inputStream()).use {after->
            fun missing(from: DirectoryEntry,to: DirectoryEntry,path: String) {
                for(entry in from) {
                    val name=path+entry.name
                    if(name in setOf("FileHeader","DocInfo","PrvText","PrvImage") || name.startsWith("BodyText") || name.startsWith("ViewText")) continue
                    if(entry is DirectoryEntry) {
                        val target=if(to.hasEntry(entry.name)) to.getEntry(entry.name) as? DirectoryEntry else to.createDirectory(entry.name)
                        if(target!=null) missing(entry,target,"$name/")
                    } else if(!to.hasEntry(entry.name)) org.apache.poi.poifs.filesystem.DocumentInputStream(entry as org.apache.poi.poifs.filesystem.DocumentEntry).use {to.createDocument(entry.name,it)}
                }
            }
            missing(before.root,after.root,"");output.outputStream().use {after.writeFilesystem(it)}
        }}
    }
}
