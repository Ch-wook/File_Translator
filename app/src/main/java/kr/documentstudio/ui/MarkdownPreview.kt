package kr.documentstudio.ui

import android.content.Intent
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
import kr.documentstudio.data.MarkdownTasks
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.ext.gfm.tables.TablesExtension

@Composable
fun MarkdownPreview(text: String,busy: Boolean=false,onChange: (String)->Unit={},viewportKey: String?=null,dark: Boolean=false) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val preferences=remember {context.getSharedPreferences("reading-position",0)}
    val token=remember {java.util.UUID.randomUUID().toString()}
    val latest by rememberUpdatedState(text);val change by rememberUpdatedState(onChange);val disabled by rememberUpdatedState(busy)
    val displayed=remember {arrayOf("")}
    val html by produceState("",text,dark) {
        delay(150)
        value=withContext(Dispatchers.Default) {
            val extensions=listOf(TablesExtension.create())
            val parser=Parser.builder().extensions(extensions).build()
            val renderer=HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build()
            """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'"><style>body{font:16px sans-serif;line-height:1.7;padding:12px;color:${if(dark)"#e7eaf0" else "#24382f"};background:${if(dark)"#1b1e24" else "#fff"};overflow-wrap:anywhere}code{font-family:monospace}pre{line-height:1.5;background:${if(dark)"#303640" else "#f2f2ed"};padding:12px;overflow:auto}blockquote{border-left:3px solid #315d54;padding-left:12px}table{border-collapse:collapse;display:block;max-width:100%;overflow-x:auto}td,th{padding:8px;border:1px solid #ccc}a{color:#315d54}img{display:none}</style></head><body>${MarkdownTasks.html(text,token)}</body></html>"""
        }
    }
    AndroidView(factory={context -> WebView(context).apply {
        settings.javaScriptEnabled=false;settings.allowFileAccess=false;settings.allowContentAccess=false
        settings.blockNetworkLoads=true;settings.blockNetworkImage=true;settings.domStorageEnabled=false
        webViewClient=object: WebViewClient() {
            override fun onPageFinished(view: WebView,url: String?) {if(viewportKey!=null)view.scrollTo(0,preferences.getInt(viewportKey+":mdY",0))}
            override fun shouldOverrideUrlLoading(view: WebView?,request: WebResourceRequest?): Boolean {
                val uri=request?.url
                if(!disabled && uri?.scheme=="studio-task" && uri.host==token && view?.tag!=null) {
                    val offset=uri.lastPathSegment?.toIntOrNull()
                    if(offset!=null && latest==displayed[0]) runCatching {MarkdownTasks.toggle(latest,offset)}.onSuccess(change)
                }
                if(uri?.scheme in listOf("https","http","mailto"))runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,uri))}
                return true
            }
        }
    }},update={if(it.tag!=html) {displayed[0]=text;it.loadDataWithBaseURL(null,html,"text/html","UTF-8",null);it.tag=html}},onRelease={if(viewportKey!=null)preferences.edit().putInt(viewportKey+":mdY",it.scrollY).apply();it.destroy()},modifier=Modifier.fillMaxSize())
}
