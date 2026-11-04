package kr.documentstudio.data

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TreeEntry(val id: String,val name: String,val directory: Boolean,val uri: Uri,val size: Long=0)
data class TreeListing(val entries: List<TreeEntry>,val truncated: Boolean=false,val scanned: Int=0)

fun interface TreeSource {suspend fun children(id: String): List<TreeEntry>}

/** Traverses only the user-selected tree; provider IDs are opaque and may contain '/'. */
class DocumentTree(private val source: TreeSource) {
    suspend fun list(parent: String,query: String,recursive: Boolean): TreeListing {
        val pending=ArrayDeque<Pair<String,Int>>();pending.add(parent to 0)
        val visited=HashSet<String>();val foundIds=HashSet<String>();val found=ArrayList<TreeEntry>();var scanned=0;var limited=false
        while(pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (id,depth)=pending.removeFirst()
            if(!visited.add(id)) continue
            if(visited.size>2000) {limited=true;break}
            for(entry in source.children(id)) {
                currentCoroutineContext().ensureActive()
                if(++scanned>20000) {limited=true;break}
                if(entry.name.contains(query,ignoreCase=true) && foundIds.add(entry.id)) {
                    if(found.size>=500) {limited=true;break}
                    found.add(entry)
                }
                if(recursive && entry.directory && entry.id !in visited) {
                    if(depth<16) pending.add(entry.id to depth+1) else limited=true
                }
            }
            if(scanned>20000 || found.size>=500) {limited=limited || pending.isNotEmpty();break}
            if(!recursive) break
        }
        return TreeListing(found.sortedWith(compareBy<TreeEntry> {!it.directory}.thenBy(String.CASE_INSENSITIVE_ORDER) {it.name}),limited,scanned.coerceAtMost(20000))
    }
}

/** Short-lived bounded folder snapshots; never caches document bytes or recovery data. */
class CachedTreeSource(private val source: TreeSource,private val now: ()->Long={System.nanoTime()/1_000_000}): TreeSource {
    private data class Snapshot(val time: Long,val entries: List<TreeEntry>)
    private val snapshots=linkedMapOf<String,Snapshot>()
    private val mutex=Mutex()
    override suspend fun children(id: String): List<TreeEntry> = mutex.withLock {
        snapshots[id]?.takeIf {now()-it.time in 0..5000}?.let {return@withLock it.entries}
        val entries=source.children(id)
        currentCoroutineContext().ensureActive()
        snapshots.remove(id)
        if(entries.size<=2000) {
            snapshots[id]=Snapshot(now(),entries)
            while(snapshots.size>8 || snapshots.values.sumOf {it.entries.size}>2000) snapshots.remove(snapshots.keys.first())
        }
        entries
    }
}

class SafTreeSource(private val resolver: ContentResolver,private val tree: Uri): TreeSource {
    init {require(tree.scheme=="content" && DocumentsContract.isTreeUri(tree))}
    override suspend fun children(id: String): List<TreeEntry> = coroutineScope {
        val signal=CancellationSignal()
        val read=async(Dispatchers.IO) {
            val result=ArrayList<TreeEntry>()
            val uri=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id)
            val columns=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE)
            val cursor=resolver.query(uri,columns,null,null,null,signal) ?: error("폴더를 읽을 수 없습니다.")
            cursor.use {
                while(it.moveToNext()) {
                    signal.throwIfCanceled()
                    require(result.size<20001) {"폴더 항목이 너무 많습니다. 더 작은 폴더를 선택해 주세요."}
                    val child=it.getString(0) ?: continue
                    require(child.length<=4096)
                    result.add(TreeEntry(child,(it.getString(1) ?: "이름 없음").take(512),it.getString(2)==DocumentsContract.Document.MIME_TYPE_DIR,
                        DocumentsContract.buildDocumentUriUsingTree(tree,child),if(it.isNull(3)) 0 else it.getLong(3)))
                }
            }
            result
        }
        try {read.await()} finally {signal.cancel()}
    }
}
