package kr.documentstudio.core

import java.io.File

/** Read-only corpus diagnostic; every input is pinned by Git blob in the manifest. */
object DocumentOpenCorpus {
    @JvmStatic fun main(args: Array<String>) {
        val root=File("../artifacts/open-corpus")
        require(root.isDirectory) {"Pinned corpus not downloaded"}
        val results=mutableListOf("file\tstage\tresult")
        root.listFiles()!!.filter {it.extension=="hwp"}.sortedBy {it.name}.forEach {file->
            for(stage in listOf("detect","extract")) {
                val answer=try {
                    if(stage=="detect") "OK "+FormatDetector.detect(file,file.name)
                    else "OK sections="+LegacyDocument.open(file).sections.size
                } catch(e: Exception) {e.javaClass.simpleName+": "+e.message}
                results.add(file.name+"\t"+stage+"\t"+answer.replace('\n',' '))
                println("OPEN_CORPUS "+results.last())
            }
        }
        File(root,"host-results.tsv").writeText(results.joinToString("\n")+"\n")
    }
}
