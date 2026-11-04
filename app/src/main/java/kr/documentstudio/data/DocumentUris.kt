package kr.documentstudio.data

import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** A tree/document URI and a direct document URI can address the same original. */
object DocumentUris {
    fun same(left: String?,right: String?): Boolean {
        if(left==null || right==null)return false
        val a=Uri.parse(left).normalizeScheme();val b=Uri.parse(right).normalizeScheme()
        if(a==b)return true
        if(a.scheme=="file" && b.scheme=="file")return runCatching {File(a.path!!).canonicalFile==File(b.path!!).canonicalFile}.getOrDefault(false)
        if(a.scheme!="content" || b.scheme!="content" || a.authority!=b.authority)return false
        return runCatching {
            val x=DocumentsContract.getDocumentId(a);val y=DocumentsContract.getDocumentId(b)
            x.isNotEmpty() && x==y
        }.getOrDefault(false)
    }
}
