package kr.documentstudio.ui

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.ext.gfm.tables.TablesExtension

@Composable
fun MarkdownPreview(text: String) {
    val html by produceState("",text) {
        delay(150)
        value=withContext(Dispatchers.Default) {
            val extensions=listOf(TablesExtension.create())
            val parser=Parser.builder().extensions(extensions).build()
            val renderer=HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build()
            """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'"><style>body{font:16px sans-serif;line-height:1.7;padding:20px;color:#24382f;overflow-wrap:anywhere}pre{background:#f2f2ed;padding:12px;overflow:auto}blockquote{border-left:3px solid #315d54;padding-left:12px}table{border-collapse:collapse}td,th{padding:8px;border:1px solid #ccc}a{color:#315d54}img{display:none}</style></head><body>${renderer.render(parser.parse(text))}</body></html>"""
        }
    }
    AndroidView(factory={context -> WebView(context).apply {
        settings.javaScriptEnabled=false;settings.allowFileAccess=false;settings.allowContentAccess=false
        settings.blockNetworkLoads=true;settings.blockNetworkImage=true;settings.domStorageEnabled=false
        webViewClient=object: WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?,request: WebResourceRequest?)=true
        }
    }},update={if(it.tag!=html) {it.loadDataWithBaseURL(null,html,"text/html","UTF-8",null);it.tag=html}},onRelease={it.destroy()},modifier=Modifier.fillMaxSize())
}
