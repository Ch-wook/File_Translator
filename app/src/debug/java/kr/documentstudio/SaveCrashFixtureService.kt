package kr.documentstudio

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.net.Uri
import kr.documentstudio.data.*
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Debug-only process-death fixture. Not exported and absent from release manifests. */
class SaveCrashFixtureService: Service() {
    override fun onBind(intent: Intent): IBinder {
        check(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
        val token=requireNotNull(intent.getStringExtra("token"));require(UUID.fromString(token).toString()==token)
        val owner=requireNotNull(intent.getStringExtra("owner"));require(UUID.fromString(owner).toString()==owner)
        val complete=intent.getBooleanExtra("complete",false)
        val dir=File(cacheDir,"tests/savekill-"+token)
        if(!File(dir,"crashed.json").exists())Thread {
            val target=File(dir,"target.txt");val payload=File(dir,"payload.txt")
            val receipt=ProviderWrites(this).prepare(owner,payload,Uri.fromFile(target),DocumentRepository.digest(target),"프로세스 종료 시험.txt","text/plain")
            RandomAccessFile(target,"rw").use {file->
                file.setLength(0)
                file.write(if(complete)payload.readBytes() else "partial".toByteArray());file.fd.sync()
            }
            DocumentRepository.atomic(File(dir,"crashed.json"),JSONObject().put("id",receipt.recovery.id).put("pid",Process.myPid()).put("complete",complete).toString().toByteArray())
            Process.killProcess(Process.myPid())
        }.start()
        return Binder()
    }
}
