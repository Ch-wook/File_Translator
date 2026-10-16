package kr.documentstudio.data

import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.*
import kr.documentstudio.core.DocumentChange
import java.io.File
import java.util.IdentityHashMap

data class PdfBookmark(val id: String,val title: String,val page: Int,val depth: Int)

object PdfBookmarks {
    private data class Node(val id: String,val item: PDOutlineItem,val children: List<Node>)
    private fun tree(doc: PDDocument): List<Node> {
        val visited=java.util.Collections.newSetFromMap(IdentityHashMap<COSDictionary,Boolean>());var count=0
        fun children(parent: PDOutlineNode,prefix: String,depth: Int): List<Node> {
            require(depth<=32);val result=mutableListOf<Node>();var child=parent.firstChild;var index=0
            while(child!=null) {require(++count<=5000 && visited.add(child.cosObject)) {"북마크 순환 또는 개수 제한을 초과했습니다."};val id=if(prefix.isEmpty()) "$index" else "$prefix/$index";result.add(Node(id,child,children(child,id,depth+1)));child=child.nextSibling;index++};return result
        }
        return doc.documentCatalog.documentOutline?.let {children(it,"",0)} ?: emptyList()
    }
    fun list(file: File): List<PdfBookmark> = PDDocument.load(file).use {doc->
        val pages=doc.pages.mapIndexed {i,page->page.cosObject to i}.toMap();val result=mutableListOf<PdfBookmark>()
        fun add(nodes: List<Node>,depth: Int) {nodes.forEach {n->val page=runCatching {n.item.findDestinationPage(doc)}.getOrNull();result.add(PdfBookmark(n.id,n.item.title ?: "제목 없음",page?.let {pages[it.cosObject]} ?: -1,depth));add(n.children,depth+1)}}
        add(tree(doc),0);result
    }
    fun change(doc: PDDocument,page: Int,change: DocumentChange) {
        val nodes=tree(doc);val a=change.arguments
        fun flatten(nodes: List<Node>): List<Node> = nodes.flatMap {listOf(it)+flatten(it.children)}
        when(change.type) {
            "pdf.bookmarkAdd" -> {val title=a.getValue("title");require(title.isNotBlank() && title.length<=1024);val root=doc.documentCatalog.documentOutline ?: PDDocumentOutline().also {doc.documentCatalog.documentOutline=it};root.addLast(PDOutlineItem().apply {setTitle(title);setDestination(doc.getPage(page))});root.openNode()}
            "pdf.bookmarkRename" -> {val title=a.getValue("title");require(title.isNotBlank() && title.length<=1024);(flatten(nodes).firstOrNull {it.id==a["id"]} ?: error("북마크를 찾을 수 없습니다.")).item.title=title}
            "pdf.bookmarkDelete" -> {
                val id=a.getValue("id");require(flatten(nodes).any {it.id==id});val original=doc.documentCatalog.documentOutline!!
                fun detached(dictionary: COSDictionary)=COSDictionary(dictionary).apply {listOf(COSName.FIRST,COSName.LAST,COSName.PARENT,COSName.NEXT,COSName.PREV,COSName.COUNT).forEach(::removeItem)}
                val root=PDDocumentOutline(detached(original.cosObject))
                fun copy(parent: PDOutlineNode,nodes: List<Node>) {nodes.filterNot {it.id==id}.forEach {n->val item=PDOutlineItem(detached(n.item.cosObject));parent.addLast(item);copy(item,n.children);if(n.item.isNodeOpen) item.openNode() else item.closeNode()}}
                copy(root,nodes);doc.documentCatalog.documentOutline=root
            }
        }
    }
    fun beforePageDeletion(doc: PDDocument,index: Int) {
        val removed=doc.getPage(index).cosObject;val replacement=doc.getPage(if(index+1<doc.numberOfPages) index+1 else index-1)
        fun repair(nodes: List<Node>) {nodes.forEach {n->if(runCatching {n.item.findDestinationPage(doc)?.cosObject===removed}.getOrDefault(false)) {n.item.cosObject.removeItem(COSName.A);n.item.setDestination(replacement)};repair(n.children)}}
        repair(tree(doc))
    }
}
