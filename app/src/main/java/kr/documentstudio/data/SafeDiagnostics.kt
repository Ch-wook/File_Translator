package kr.documentstudio.data

import android.app.Application
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File

/** Local diagnostics never include exception messages, stack traces, document names or URI values. */
object SafeDiagnostics {
    @Volatile private var installed=false
    fun install(app: Application) {
        if(installed)return
        installed=true
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler {thread,error->
            runCatching {record(app,error,true)}
            previous?.uncaughtException(thread,error)
        }
    }
    fun record(context: Context,error: Throwable,fatal: Boolean=false) {
        runCatching {
            val process=Application.getProcessName().let {when {it.endsWith(":hangul")->"hangul";it.endsWith(":office")->"office";else->"host"}}
            val file=File(context.filesDir,"diagnostics/last-"+process+".json")
            val version=context.packageManager.getPackageInfo(context.packageName,0).versionName ?: "unknown"
            val data=JSONObject().put("version",1).put("time",System.currentTimeMillis()).put("api",Build.VERSION.SDK_INT)
                .put("appVersion",version).put("component",process).put("fatal",fatal).put("code",UserProblems.explain(error).code)
            DocumentRepository.atomic(file,data.toString().toByteArray())
        }
    }
    fun text(context: Context): String {
        val records=listOf("host","hangul","office").mapNotNull {component->
            val file=File(context.filesDir,"diagnostics/last-"+component+".json")
            runCatching {if(file.length() !in 1L..4096L)null else JSONObject(android.util.AtomicFile(file).openRead().use {it.bufferedReader().readText()})}.getOrNull()
        }
        return "로컬 진단 · 예외 원문·문서명·경로·서명은 포함하지 않습니다.\n"+records.joinToString("\n"){j->
            listOf("time","api","appVersion","component","fatal","code").joinToString(" · "){key->key+"="+j.opt(key)}
        }.ifEmpty {"보관된 오류 기록이 없습니다."}
    }
}
