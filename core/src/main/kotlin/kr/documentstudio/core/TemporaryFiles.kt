package kr.documentstudio.core

import java.io.File

data class CleanupResult(val files: Int,val bytes: Long)
object TemporaryFiles {
    /** Only transient names created by this application, older than a day. */
    fun cleanup(cache: File,documents: File,active: Set<File> = emptySet(),now: Long=System.currentTimeMillis()): CleanupResult {
        val protected=active.map {it.canonicalFile}.toSet();var count=0;var bytes=0L;var scanned=0
        val transient=Regex("(?:materialized|attachment|ocr-font|image-source|image|backup-export|backup-encrypted|backup-unlock|crypto-stage)-[a-zA-Z0-9-]+\\.(?:bin|png|ttf|zip|dsbackup)")
        fun clean(directory: File,cacheFiles: Boolean) {
            val parent=directory.canonicalFile
            for(file in directory.listFiles().orEmpty()) {
                if(++scanned>10000) break
                val expected=if(cacheFiles) file.name.matches(Regex("print-[a-zA-Z0-9-]+\\.pdf")) else file.name.matches(transient)
                if(expected && file.isFile && file.canonicalFile.parentFile==parent && file.canonicalFile !in protected && file.lastModified()<=now-86400000L) {
                    val size=file.length();if(file.delete()) {count++;bytes+=size}
                }
            }
        }
        clean(cache,true)
        val root=documents.canonicalFile
        for(directory in documents.listFiles().orEmpty()) {
            if(scanned>=10000) break
            if(directory.isDirectory && directory.name.matches(Regex("[a-fA-F0-9-]{36}")) && directory.canonicalFile.parentFile==root) clean(directory,false)
        }
        return CleanupResult(count,bytes)
    }
}
