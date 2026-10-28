package kr.documentstudio

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.AtomicFile
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kr.documentstudio.core.FormatDetector
import kr.documentstudio.core.LegacyDocument
import kr.documentstudio.core.HangulPreservation
import org.json.JSONObject
import java.io.*
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Offline, origin-isolated paginated HWP/HWPX engine. Only the private working copy is exposed. */
class HangulEditorActivity: ComponentActivity() {
    private lateinit var working: File
    private lateinit var browser: WebView
    private lateinit var sessionId: String
    private lateinit var extension: String
    private var readOnlyReason: String?=null
    private var countedHost=false
    private var lockFile: RandomAccessFile?=null
    private var lock: FileLock?=null
    private var staged: File?=null
    private var output: FileOutputStream?=null
    private var expected=0L
    private var written=0L
    @Volatile private var ready=false
    @Volatile private var editAllowed=false
    @Volatile private var loadError: String?=null
    private val bridge=Bridge()
    private var fileResult: ValueCallback<Array<Uri>>?=null
    private val imagePicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) {uri->fileResult?.onReceiveValue(uri?.let {arrayOf(it)});fileResult=null}
    private var pdfSession: kr.documentstudio.data.RasterPdfSession?=null
    private var pdfFile: File?=null
    private var pdfUri: Uri?=null
    private var convertedUri: String?=null
    private val pdfPicker=registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) {uri->
        if(uri!=null){pdfUri=uri;browser.evaluateJavascript("window.documentStudioHost.writePdf()",null)}
    }
    companion object {private const val ORIGIN="https://hangul.documentstudio.local";private var profile=false;private val activeHosts=java.util.concurrent.atomic.AtomicInteger()}

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        activeHosts.incrementAndGet();countedHost=true
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
        if(!profile) {WebView.setDataDirectorySuffix("hangul");profile=true}
        sessionId=requireNotNull(intent.getStringExtra("sessionId")).also {require(it.matches(Regex("[a-zA-Z0-9-]+")))}
        val directory=File(filesDir,"documents/$sessionId").canonicalFile
        working=File(requireNotNull(intent.getStringExtra("workingPath"))).canonicalFile
        require(working.parentFile==directory && working.name.matches(Regex("office-[a-zA-Z0-9-]+\\.(hwp|hwpx)")) && working.isFile)
        extension=working.extension
        readOnlyReason=if(extension=="hwp") LegacyDocument.hwpReadOnlyReason(working) else null
        lockFile=RandomAccessFile(File(directory,"office.lock"),"rw")
        lock=lockFile!!.channel.tryLock();check(lock!=null) {"이미 편집 중인 문서입니다."}
        setResult(Activity.RESULT_CANCELED,Intent().putExtra("sessionId",sessionId))
        browser=WebView(this).apply {
            settings.javaScriptEnabled=true;settings.domStorageEnabled=true
            settings.allowFileAccess=false;settings.allowContentAccess=true // Only picker-granted images; navigations remain origin restricted.
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            addJavascriptInterface(bridge,"DocumentStudio")
            webViewClient=object: WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest)=request.url.scheme!="https" || request.url.host!="hangul.documentstudio.local"
                override fun shouldInterceptRequest(view: WebView,request: WebResourceRequest): WebResourceResponse {
                    val uri=request.url
                    fun denied()=WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(byteArrayOf()))
                    if(uri.scheme!="https" || uri.host!="hangul.documentstudio.local" || request.method!="GET") return denied()
                    val path=uri.path ?: return denied()
                    if(path.split('/').any {it==".." || it.contains('\\')}) return denied()
                    return try {
                        if(path=="/document") WebResourceResponse("application/octet-stream",null,working.inputStream())
                        else {
                            val relative=if(path=="/") "index.html" else path.removePrefix("/")
                            val mime=when(relative.substringAfterLast('.')) {"html"->"text/html";"js","mjs"->"text/javascript";"css"->"text/css";"wasm"->"application/wasm";"woff2"->"font/woff2";"woff"->"font/woff";"ttf"->"font/ttf";"svg"->"image/svg+xml";"png"->"image/png";"json"->"application/json";else->"application/octet-stream"}
                            WebResourceResponse(mime,if(mime.startsWith("text/")) "UTF-8" else null,assets.open("hangul/$relative"))
                        }.apply {responseHeaders=mapOf("Cache-Control" to "no-store","X-Content-Type-Options" to "nosniff","Content-Security-Policy" to "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; font-src 'self' data: blob:; img-src 'self' data: blob:; connect-src 'self'; frame-src 'self'; worker-src 'none'")}
                    } catch(_: Exception) {WebResourceResponse("text/plain","UTF-8",404,"Not found",emptyMap(),ByteArrayInputStream(byteArrayOf()))}
                }
            }
            webChromeClient=object: WebChromeClient() {
                override fun onShowFileChooser(view: WebView,callback: ValueCallback<Array<Uri>>,params: FileChooserParams): Boolean {
                    if(!editAllowed) {callback.onReceiveValue(null);return true}
                    fileResult?.onReceiveValue(null);fileResult=callback;imagePicker.launch(arrayOf("image/*"));return true
                }
            }
        }
        // WebView padding does not reliably move its rendered page and accessibility bounds together.
        // Apply insets to the parent so touch and accessibility target the same document controls.
        val container=android.widget.FrameLayout(this).apply {
            addView(browser,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(container)
        ViewCompat.setOnApplyWindowInsetsListener(container) {view,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard=insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,keyboard.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(container)
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true) {override fun handleOnBackPressed() {
            if(!ready) bridge.closeEditor(false) else browser.evaluateJavascript("window.documentStudioHost.back()",null)
        }})
        browser.loadUrl("$ORIGIN/")
    }
    override fun onPause() {
        if(ready && ::browser.isInitialized) browser.evaluateJavascript("window.documentStudioHost?.checkpoint()",null)
        super.onPause()
    }
    private fun <T> clipboardOnUi(work: ()->T): T {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) return work()
        val task=java.util.concurrent.FutureTask<T>(java.util.concurrent.Callable {work()})
        runOnUiThread(task)
        return task.get(3,java.util.concurrent.TimeUnit.SECONDS)
    }
    inner class Bridge {
        @JavascriptInterface fun readSignatures(): String = runCatching {
            val file=File(filesDir,"signatures.json");if(file.isFile && file.length()<=4*1024*1024) AtomicFile(file).openRead().use {it.bufferedReader().readText()} else "[]"
        }.getOrDefault("[]")
        @JavascriptInterface fun writeSignatures(value: String): Boolean = runCatching {
            require(ready && editAllowed && value.toByteArray().size<=4*1024*1024)
            val entries=org.json.JSONArray(value);require(entries.length()<=20)
            for(i in 0 until entries.length()){val s=entries.getJSONObject(i);require(s.getString("png").startsWith("data:image/png;base64,") && s.getString("name").length<=80)}
            val file=AtomicFile(File(filesDir,"signatures.json"));val out=file.startWrite()
            try{out.write(value.toByteArray());file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e};true
        }.getOrDefault(false)
        @JavascriptInterface fun requestPdf(){if(ready && loadError==null && readOnlyReason==null)runOnUiThread {convertedUri=null;pdfPicker.launch(intent.getStringExtra("displayName")?.substringBeforeLast('.')+".pdf")}}
        @JavascriptInterface fun beginPdf(count: Int): Boolean = runCatching {
            require(ready && loadError==null && readOnlyReason==null && pdfUri!=null);abortPdf()
            val file=File.createTempFile("hangul-conversion-",".pdf",cacheDir);pdfFile=file;pdfSession=kr.documentstudio.data.RasterPdfSession(file,count);true
        }.getOrDefault(false)
        @JavascriptInterface fun pdfPage(json: String): Boolean = runCatching {requireNotNull(pdfSession).page(json);true}.getOrDefault(false)
        @JavascriptInterface fun finishPdf(): String = try {
            val pages=requireNotNull(pdfSession).finish();val uri=requireNotNull(pdfUri)
            kr.documentstudio.data.ConversionFiles.publish(this@HangulEditorActivity,requireNotNull(pdfFile),uri,intent.getStringExtra("originalUri"))
            convertedUri=uri.toString();abortPdf()
            runOnUiThread {AlertDialog.Builder(this@HangulEditorActivity).setTitle("PDF 변환 완료").setMessage("${pages}쪽 PDF를 저장하고 다시 읽어 검증했습니다.").setPositiveButton("결과 열기"){_,_->startActivity(Intent(this@HangulEditorActivity,ConversionPreviewActivity::class.java).setData(uri))}.setNegativeButton("닫기",null).show()}
            JSONObject().put("ok",true).toString()
        }catch(e:Exception){abortPdf();JSONObject().put("ok",false).put("error",e.message).toString()}
        @JavascriptInterface fun abortPdf(){runCatching {pdfSession?.close()};pdfSession=null;pdfFile?.delete();pdfFile=null}

        @JavascriptInterface fun writeClipboard(value: String): Boolean = runCatching {
            require(ready && editAllowed && loadError==null && value.length<=2*1024*1024)
            val payload=JSONObject(value);val text=payload.optString("text");val html=payload.optString("html")
            require(text.isNotEmpty() || html.isNotEmpty())
            clipboardOnUi {
                val manager=getSystemService(android.content.ClipboardManager::class.java)
                manager.setPrimaryClip(if(html.isNotEmpty()) android.content.ClipData.newHtmlText("문서 스튜디오",text,html)
                    else android.content.ClipData.newPlainText("문서 스튜디오",text))
            };true
        }.getOrDefault(false)
        @JavascriptInterface fun readClipboard(): String = runCatching {
            require(ready && editAllowed && loadError==null)
            clipboardOnUi {
                val clip=getSystemService(android.content.ClipboardManager::class.java).primaryClip
                val item=clip?.takeIf {it.itemCount>0}?.getItemAt(0)
                val text=item?.text?.toString() ?: ""
                val html=item?.htmlText ?: ""
                require(text.length+html.length<=2*1024*1024) {"클립보드 내용이 너무 큽니다."}
                JSONObject().put("text",text).put("html",html).toString()
            }
        }.getOrElse {JSONObject().put("error",it.message ?: "클립보드를 읽지 못했습니다.").toString()}

        @JavascriptInterface fun describe(): String {
            val meta=JSONObject().put("name",intent.getStringExtra("displayName") ?: working.name)
                .put("format",extension).put("conversionOnly",intent.getBooleanExtra("conversionOnly",false)).put("openIntent",if(readOnlyReason==null && intent.getStringExtra("openIntent")=="EDIT") "EDIT" else "VIEW")
                .put("readOnlyReason",readOnlyReason ?: JSONObject.NULL)
            runCatching {
                val file=File(working.parentFile,"hangul-view-state.json")
                if(file.isFile && file.length()<=65536) meta.put("viewState",JSONObject(AtomicFile(file).openRead().bufferedReader().use {it.readText()}))
            }
            return meta.toString()
        }
        @Synchronized @JavascriptInterface fun setEditing(value: Boolean): Boolean {
            if(value && intent.getBooleanExtra("conversionOnly",false))return false
            if(!ready || output!=null || loadError!=null || (value && readOnlyReason!=null)) return false
            editAllowed=value
            return true
        }
        @Synchronized @JavascriptInterface fun saveViewState(value: String) {
            if(!ready || value.length>65536) return
            val view=runCatching {JSONObject(value)}.getOrNull() ?: return
            val zoom=view.optDouble("zoom",1.0)
            val top=view.optDouble("scrollTop",0.0);val left=view.optDouble("scrollLeft",0.0)
            if(!zoom.isFinite() || zoom !in 0.1..5.0 || !top.isFinite() || !left.isFinite() || top<0 || left<0) return
            val state=JSONObject().put("zoom",zoom).put("scrollTop",top).put("scrollLeft",left).toString()
            val atomic=AtomicFile(File(working.parentFile,"hangul-view-state.json"))
            var stream: FileOutputStream?=null
            try {stream=atomic.startWrite();stream.write(state.toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}
            catch(_: Exception) {stream?.let {atomic.failWrite(it)}}
        }
        @JavascriptInterface fun loaded() {ready=true}
        @JavascriptInterface fun loadFailed(message: String) {loadError=message.take(500);editAllowed=false;runOnUiThread {Toast.makeText(this@HangulEditorActivity,loadError,Toast.LENGTH_LONG).show()}}
        @Synchronized @JavascriptInterface fun beginWrite(length: Int): Boolean {
            abortWrite();if(!ready || !editAllowed || loadError!=null || readOnlyReason!=null || length !in 1..128*1024*1024) return false
            expected=length.toLong();written=0
            staged=File(working.parentFile,"hangul-${java.util.UUID.randomUUID()}.tmp")
            return try {output=FileOutputStream(staged!!);true} catch(_: Exception) {abortWrite();false}
        }
        @Synchronized @JavascriptInterface fun writeChunk(value: String): Boolean = try {
            require(editAllowed && value.length<=65536);val bytes=Base64.decode(value,Base64.NO_WRAP)
            require(output!=null && written+bytes.size<=expected);output!!.write(bytes);written+=bytes.size;true
        } catch(_: Exception) {abortWrite();false}
        @Synchronized @JavascriptInterface fun finishWrite(report: String): String = try {
            require(ready && editAllowed && loadError==null && report.length<=65536);val loss=JSONObject(report)
            require(loss.getInt("schemaVersion")==1 && loss.getString("outputFormat")==extension && loss.getInt("count")==0 && loss.getJSONArray("losses").length()==0) {"문서 요소 보존 검사에 실패했습니다."}
            require(written==expected && output!=null && staged!=null)
            output!!.fd.sync();output!!.close();output=null
            require(FormatDetector.detect(staged!!,working.name).extension==extension) {"저장 결과 형식이 원본과 다릅니다."}
            val sealed=File(working.parentFile,"hangul-sealed-${java.util.UUID.randomUUID()}.tmp")
            try {
                HangulPreservation.seal(working,staged!!,sealed)
                FileOutputStream(sealed,true).use {it.fd.sync()}
                Files.move(sealed.toPath(),working.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            } finally {sealed.delete()}
            FileOutputStream(File(working.path+".edited")).use {it.write(1);it.fd.sync()}
            staged!!.delete();staged=null;JSONObject().put("ok",true).toString()
        } catch(error: Exception) {abortWrite();JSONObject().put("ok",false).put("error",error.message ?: "저장 검증 실패").toString()}
        @Synchronized @JavascriptInterface fun abortWrite() {runCatching {output?.close()};output=null;staged?.delete();staged=null;expected=0;written=0}
        @JavascriptInterface fun requestDiscard() {runOnUiThread {
            AlertDialog.Builder(this@HangulEditorActivity).setTitle("저장되지 않은 변경 취소")
                .setMessage("마지막으로 저장한 복구본으로 돌아갑니다. 저장 검사를 통과하지 못한 최근 변경은 버려집니다. 원본 파일은 바뀌지 않습니다.")
                .setNegativeButton("계속 편집",null).setPositiveButton("변경 취소 후 돌아가기") {_,_->closeEditor(false)}.show()
        }}
        @JavascriptInterface fun closeEditor(saveOriginal: Boolean) {runOnUiThread {
            FileOutputStream(File(working.path+".closed")).use {it.write(1);it.fd.sync()}
            editAllowed=false
            val changed=File(working.path+".edited").isFile
            val result=Intent().putExtra("sessionId",sessionId).putExtra("saveOriginal",saveOriginal && changed && readOnlyReason==null).putExtra("edited",changed)
            convertedUri?.let {result.putExtra("conversionUri",it)}
            loadError?.let {result.putExtra("error",it)}
            setResult(if(loadError==null) Activity.RESULT_OK else Activity.RESULT_CANCELED,result);finish()
        }}
    }
    override fun onDestroy() {
        bridge.abortPdf()
        bridge.abortWrite();fileResult?.onReceiveValue(null);fileResult=null
        if(::browser.isInitialized) {browser.removeJavascriptInterface("DocumentStudio");browser.destroy()}
        runCatching {lock?.release();lockFile?.close()}
        super.onDestroy()
        val remaining=if(countedHost) {countedHost=false;activeHosts.decrementAndGet()} else activeHosts.get()
        // Closing an older window must not terminate another document in :hangul.
        if(isFinishing && remaining==0) android.os.Process.killProcess(android.os.Process.myPid())
    }
}
