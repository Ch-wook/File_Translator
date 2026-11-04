package kr.documentstudio.ui

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kr.documentstudio.data.MarkdownTasks
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

class MarkdownReaderController {
    private var view=WeakReference<WebView>(null)
    internal fun bind(value: WebView){view=WeakReference(value)}
    internal fun release(value: WebView){if(view.get()===value)view.clear()}
    fun navigate(end: Boolean){view.get()?.let {if(end)it.pageDown(true) else it.scrollTo(0,0)}}
}

/** Formatted, offline reading. Display fonts/zoom never modify Markdown bytes. */
@Composable
fun MarkdownPreview(text: String,busy: Boolean=false,onChange: (String)->Unit={},viewportKey: String?=null,dark: Boolean=false,
                    displaySize: Float=16f,displayFont: String="시스템 고딕",controller: MarkdownReaderController?=null) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val preferences=remember {context.getSharedPreferences("reading-position",0)}
    val token=remember(viewportKey) {java.util.UUID.randomUUID().toString()}
    val latest by rememberUpdatedState(text);val change by rememberUpdatedState(onChange);val disabled by rememberUpdatedState(busy)
    val displayed=remember(viewportKey) {arrayOf("")}
    val pendingY=remember(viewportKey){intArrayOf(-1)}
    val textZoom=(displaySize.coerceIn(8f,72f)/16f*100f*LocalDensity.current.fontScale).roundToInt().coerceIn(40,900)
    val family=when(displayFont){"시스템 고딕"->"sans-serif";"시스템 바탕"->"serif";"고정폭"->"monospace";else->displayFont.takeIf {it in EditorFonts.files} ?: "sans-serif"}
    val rendered by produceState("",text,token) {
        delay(150)
        // MarkdownTasks owns the actual parser. Do not construct unused parsers
        // or reparse the document merely for a color/font/display change.
        value=withContext(Dispatchers.Default){MarkdownTasks.html(text,token)}
    }
    val html=remember(rendered,dark,family) {
        val fonts=EditorFonts.files.entries.joinToString("\n"){(name,file)->
            "@font-face{font-family:'"+name+"';src:url('https://markdown.documentstudio.local/fonts/"+file+"') format('truetype');font-display:swap}"
        }
        """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; font-src https://markdown.documentstudio.local"><style>${fonts}
        body{font-family:'${family}';font-size:16px;line-height:1.7;padding:12px;color:${if(dark)"#e7eaf0" else "#24382f"};background:${if(dark)"#1b1e24" else "#fff"};overflow-wrap:anywhere}code{font-family:monospace}pre{line-height:1.5;background:${if(dark)"#303640" else "#f2f2ed"};padding:12px;overflow:auto}blockquote{border-left:3px solid #315d54;padding-left:12px}table{border-collapse:collapse;display:block;max-width:100%;overflow-x:auto}td,th{padding:8px;border:1px solid #ccc}a{color:${if(dark)"#a8c9ff" else "#2858a5"}}img{display:none}</style></head><body>${rendered}</body></html>"""
    }
    key(viewportKey) {
      AndroidView(factory={context -> WebView(context).apply {
        settings.javaScriptEnabled=false;settings.allowFileAccess=false;settings.allowContentAccess=false
        settings.blockNetworkLoads=true;settings.blockNetworkImage=true;settings.domStorageEnabled=false
        settings.setSupportZoom(true);settings.builtInZoomControls=true;settings.displayZoomControls=false
        val persist=Runnable {if(viewportKey!=null)preferences.edit().putInt(viewportKey+":mdY",scrollY).apply()}
        setOnScrollChangeListener {_,_,_,_,_->removeCallbacks(persist);postDelayed(persist,350)}
        // The callback is removed by detach; onRelease also records the last value.
        addOnAttachStateChangeListener(object: android.view.View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v: android.view.View){}
            override fun onViewDetachedFromWindow(v: android.view.View){removeCallbacks(persist)}
        })
        webViewClient=object: WebViewClient() {
            // Local asset supply diagnostics contain font filenames only.
            val providedFonts=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            override fun onPageFinished(view: WebView,url: String?) {
                if(viewportKey!=null)view.scrollTo(0,if(pendingY[0]>=0)pendingY[0] else preferences.getInt(viewportKey+":mdY",0))
                pendingY[0]=-1
            }
            override fun shouldInterceptRequest(view: WebView?,request: WebResourceRequest?): WebResourceResponse? {
                // loadDataWithBaseURL owns the inline main document. Intercept
                // only subresources; a blanket 403 can replace that document.
                if(request?.isForMainFrame==true)return null
                val uri=request?.url
                val filename=uri?.path?.removePrefix("/fonts/")
                if(uri?.scheme=="https" && uri.host=="markdown.documentstudio.local" && uri.path=="/fonts/"+filename
                    && uri.query==null && filename in EditorFonts.files.values) {
                    runCatching {context.assets.open("fonts/"+filename)}.getOrNull()?.let {stream->filename?.let {providedFonts.add(it)};return WebResourceResponse("font/ttf",null,stream)}
                }
                return WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(byteArrayOf()))
            }
            override fun shouldOverrideUrlLoading(view: WebView?,request: WebResourceRequest?): Boolean {
                val uri=request?.url
                if(!disabled && uri?.scheme=="studio-task" && uri.host==token && view?.tag!=null) {
                    val offset=uri.lastPathSegment?.toIntOrNull()
                    if(offset!=null && latest==displayed[0])runCatching {MarkdownTasks.toggle(latest,offset)}.onSuccess(change)
                }
                if(uri?.scheme in listOf("https","http","mailto"))runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,uri))}
                return true
            }
        }
      }},update={view->
        controller?.bind(view)
        if(view.settings.standardFontFamily!=family)view.settings.standardFontFamily=family
        if(view.settings.textZoom!=textZoom)view.settings.textZoom=textZoom
        if(view.tag!=html) {
            pendingY[0]=if(view.tag==null)preferences.getInt(viewportKey+":mdY",0) else view.scrollY
            displayed[0]=text;view.loadDataWithBaseURL("https://markdown.documentstudio.local/",html,"text/html","UTF-8",null);view.tag=html
        }
      },onRelease={view->
        controller?.release(view)
        if(viewportKey!=null)preferences.edit().putInt(viewportKey+":mdY",view.scrollY).apply()
        view.stopLoading();view.destroy()
      },modifier=Modifier.fillMaxSize())
    }
}
