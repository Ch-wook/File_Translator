package kr.documentstudio

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import org.libreoffice.androidlib.LOActivity
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Same-UID instrumentation access in debug builds; never present in release. */
class OfficeProbeProvider: ContentProvider() {
    private var current=WeakReference<LOActivity>(null)
    override fun onCreate(): Boolean {
        (context!!.applicationContext as Application).registerActivityLifecycleCallbacks(object: Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity,state: Bundle?) {if(activity is LOActivity) current=WeakReference(activity)}
            override fun onActivityDestroyed(activity: Activity) {if(current.get()===activity) current.clear()}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity,state: Bundle) {}
        });return true
    }
    override fun call(method: String,arg: String?,extras: Bundle?): Bundle {
        val activity=current.get() ?: return Bundle().apply {putBoolean("ready",false)}
        fun field(name: String)=LOActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.get(activity)
        val output=Bundle().apply {putBoolean("ready",field("documentLoaded") as Boolean);putBoolean("editing",field("mIsEditModeActive") as Boolean);putBoolean("editable",field("isDocEditable") as Boolean)}
        if(method=="state") return output
        require(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
        val done=CountDownLatch(1);var failure: Throwable?=null
        activity.runOnUiThread {
            try {
                when(method) {
                    "eval" -> {require(arg!=null && arg.length<=20000);(field("mWebView") as WebView).evaluateJavascript(arg) {value->output.putString("result",value);done.countDown()};return@runOnUiThread}
                    "uno" -> {require(arg!=null && arg.matches(Regex("\\.uno:[A-Za-z0-9]+")));activity.postUnoCommand(arg,extras?.getString("arguments"),true)}
                    "paste" -> {require(arg!=null && arg.length<=10000);activity.paste("text/plain;charset=utf-8",arg.toByteArray(Charsets.UTF_8))}
                    "convert-pdf" -> {require(arg!=null && arg.startsWith("content://"+context!!.packageName+".testfiles/"));activity.onActivityResult(LOActivity.REQUEST_SAVEAS_PDF,Activity.RESULT_OK,android.content.Intent().setData(Uri.parse(arg)))}
                    "close" -> LOActivity::class.java.getDeclaredMethod("finishWithProgress").apply {isAccessible=true}.invoke(activity)
                    else -> error("Unknown debug probe action")
                }
            } catch(e: Throwable) {failure=e} finally {if(method!="eval" || failure!=null) done.countDown()}
        }
        check(done.await(15,TimeUnit.SECONDS)) {"Office probe timeout"};failure?.let {throw IllegalStateException("Office probe failed",it)};return output
    }
    override fun getType(uri: Uri)="application/octet-stream"
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?): Cursor?=null
    override fun insert(uri: Uri,values: ContentValues?): Uri?=null
    override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?)=0
    override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?)=0
}
