package kr.documentstudio

import android.net.Uri
import kotlinx.coroutines.*
import kr.documentstudio.data.*
import org.junit.Assert.*
import org.junit.Test

class DocumentTreeTest {
    private fun entry(id: String,name: String,dir: Boolean=false)=TreeEntry(id,name,dir,Uri.parse("content://test/document/"+Uri.encode(id)))
    @Test fun recursiveSearchFindsNamesPreservesOpaqueIdsAndStopsProviderCycles() = runBlocking {
        val folders=mapOf("root" to listOf(entry("opaque:a/b","폴더",true),entry("one","NOTES.MD")),
            "opaque:a/b" to listOf(entry("root","순환",true),entry("two","notes.docx"),entry("one","NOTES.MD")))
        val visited=mutableListOf<String>()
        val browser=DocumentTree {id -> visited.add(id);folders[id] ?: emptyList()}
        val result=browser.list("root","NOTES",true)
        assertEquals(setOf("one","two"),result.entries.map {it.id}.toSet());assertEquals(2,result.entries.size)
        assertEquals(listOf("root","opaque:a/b"),visited);assertFalse(result.truncated)
        assertEquals(listOf("one"),browser.list("root","notes",false).entries.map {it.id})
    }
    @Test fun resultLimitAndDirectorySortingKeepSearchBounded() = runBlocking {
        val browser=DocumentTree {List(1000) {entry("id$it","문서 $it")}}
        val result=browser.list("root","문서",false)
        assertEquals(500,result.entries.size);assertTrue(result.truncated);assertTrue(result.scanned<=501)
        val sorted=DocumentTree {listOf(entry("z","z.txt"),entry("a","a.txt"),entry("d","폴더",true))}.list("root","",false)
        assertEquals(listOf("d","a","z"),sorted.entries.map {it.id})
    }
    @Test fun repeatedQueriesReuseBoundedSnapshotsAndExpire()=runBlocking {
        var clock=0L;var reads=0
        val cache=CachedTreeSource(TreeSource {reads++;listOf(entry("one","한글.hwpx"),entry("two","notes.txt"))}) {clock}
        val browser=DocumentTree(cache)
        assertEquals(1,browser.list("root","한",false).entries.size)
        assertEquals(1,browser.list("root","notes",false).entries.size)
        assertEquals(1,reads)
        clock=5001
        assertEquals(2,browser.list("root","",false).entries.size);assertEquals(2,reads)
        repeat(9){cache.children("folder-$it")}
        cache.children("root");assertEquals(12,reads)
        val large=CachedTreeSource(TreeSource {reads++;List(2001){entry("id$it","file$it")}}) {clock}
        val before=reads;large.children("large");large.children("large");assertEquals(before+2,reads)
    }
    @Test fun cancelledProviderSearchDoesNotContinueIntoOtherFolders() = runBlocking {
        var reads=0
        val browser=DocumentTree {reads++;delay(10000);emptyList()}
        val job=launch {browser.list("root","query",true)}
        yield();job.cancelAndJoin();assertEquals(1,reads);assertTrue(job.isCancelled)
    }
}
